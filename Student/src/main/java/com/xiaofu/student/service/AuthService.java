package com.xiaofu.student.service;

import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.config.AppProperties;
import com.xiaofu.student.dto.DeviceInfoResponse;
import com.xiaofu.student.dto.LoginRequest;
import com.xiaofu.student.dto.RegisterRequest;
import com.xiaofu.student.dto.ResetTokenInfoResponse;
import com.xiaofu.student.dto.TokenResponse;
import com.xiaofu.student.dto.UserInfoResponse;
import com.xiaofu.student.entity.EmailVerificationCode;
import com.xiaofu.student.entity.PasswordResetCode;
import com.xiaofu.student.entity.PasswordResetToken;
import com.xiaofu.student.entity.RefreshToken;
import com.xiaofu.student.entity.User;
import com.xiaofu.student.entity.UserStatus;
import com.xiaofu.student.repository.EmailVerificationCodeRepository;
import com.xiaofu.student.repository.PasswordResetCodeRepository;
import com.xiaofu.student.repository.PasswordResetTokenRepository;
import com.xiaofu.student.repository.RefreshTokenRepository;
import com.xiaofu.student.repository.UserRepository;
import com.xiaofu.student.security.JwtService;
import com.xiaofu.student.security.LoginPayloadCipher;
import com.xiaofu.student.util.DeviceUtils;
import com.xiaofu.student.util.TokenUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 邮箱验证码位数。 */
    private static final int VERIFICATION_CODE_LENGTH = 6;

    /** User-Agent 入库前截断长度，与 refresh_tokens.user_agent 字段一致。 */
    private static final int MAX_USER_AGENT_LENGTH = 512;

    /**
     * 解密后账号明文的长度上限。
     *
     * <p>用户名规则是 1-18 位，但登录账号同时允许邮箱（最长 128 位），
     * 所以这里只做兜底，不对 18 位做硬限制，否则长邮箱账号将无法登录。
     */
    private static final int MAX_ACCOUNT_LENGTH = 128;

    /** 注册用户名规则：1-18 位中文、字母、数字或下划线（与前端 rules.username 一致）。 */
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_\\u4e00-\\u9fa5]{1,18}$");

    /** 注册邮箱格式：与前端 rules.email 保持一致的宽松校验。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** 注册密码规则：至少 8 位且同时包含字母和数字。 */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)\\S{8,64}$");

    /** 邮箱长度上限，与 users.email 字段长度一致。 */
    private static final int MAX_EMAIL_LENGTH = 128;

    private static final String REGISTER_MESSAGE = "如果信息有效，我们已发送验证邮件，请查收并完成邮箱验证";
    private static final String RESEND_MESSAGE = "如果该邮箱需要验证，验证邮件已发送，请查收";
    private static final String FORGOT_MESSAGE = "如果该邮箱已注册，我们已发送重置邮件";
    private static final String RESET_CODE_MESSAGE = "如果该邮箱已注册，验证码已发送，请查收";

    private final UserRepository userRepository;
    private final EmailVerificationCodeRepository emailVerificationCodeRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetCodeRepository passwordResetCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final FileStorageService fileStorageService;
    private final LoginAttemptService loginAttemptService;
    private final AuditLogger auditLogger;
    private final AppProperties appProperties;
    private final LoginPayloadCipher loginPayloadCipher;

    // ==================== 注册 ====================

    /**
     * noRollbackFor = BusinessException：这里的失败分支是「先写库再抛异常」
     * （例如邮箱已注册时补发验证码），默认回滚会把刚写的验证码一起撤掉，
     * 而邮件已经发出去了 —— 用户会收到一个库里不存在的码。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public String register(RegisterRequest request, String ip) {
        // 传输层解密：u = RC4 用户名、e = RC4 邮箱、p = RSA 密码，均带毫秒时间戳。
        // 明文规则在解密之后校验 —— DTO 上的注解只能约束密文，管不到明文。
        LoginPayloadCipher.RegisterCredentials credentials =
                loginPayloadCipher.decryptRegister(request.u(), request.e(), request.p());
        String username = credentials.username().trim();
        String email = normalizeEmail(credentials.email());
        String password = credentials.password();
        validateRegisterFields(username, email, password);

        if (userRepository.existsByUsername(username)) {
            auditLogger.log("REGISTER_REJECTED", null, username, ip, "USERNAME_TAKEN");
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getStatus() == UserStatus.PENDING && !user.isEmailVerified()) {
                // 邮箱已注册但未完成验证：拦截重复注册，同时补发验证码引导其完成验证
                issueVerificationCode(user, ip);
                auditLogger.log("REGISTER_DUPLICATE_EMAIL", user.getId(), email, ip, "PENDING_RESENT");
                throw new BusinessException(ErrorCode.EMAIL_TAKEN,
                        "该邮箱已注册但尚未完成验证，验证码已重新发送，请查收邮箱完成验证");
            }
            auditLogger.log("REGISTER_DUPLICATE_EMAIL", user.getId(), email, ip, "EMAIL_TAKEN");
            throw new BusinessException(ErrorCode.EMAIL_TAKEN, "该邮箱已被注册，请直接登录或更换邮箱");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(UserStatus.PENDING);
        user.setEmailVerified(false);
        userRepository.save(user);

        issueVerificationCode(user, ip);
        auditLogger.log("REGISTER", user.getId(), email, ip, "SUCCESS");
        return REGISTER_MESSAGE;
    }

    /**
     * 解密后的注册明文校验。
     *
     * <p>注册参数已经加密，DTO 的 {@code @Pattern} / {@code @Email} 对密文无意义，
     * 因此这些规则改到这里执行，提示措辞与前端 rules 保持一致。
     */
    private void validateRegisterFields(String username, String email, String password) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "用户名需为 1-18 位中文、字母、数字或下划线");
        }
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮箱格式不正确");
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码至少 8 位且需同时包含字母和数字");
        }
    }

    /**
     * 生成并发送 6 位邮箱验证码，带发送间隔与每日上限限制。
     */
    private void issueVerificationCode(User user, String ip) {
        Instant now = Instant.now();
        var security = appProperties.getSecurity();

        Optional<EmailVerificationCode> latest =
                emailVerificationCodeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId());
        if (latest.isPresent() && latest.get().getCreatedAt()
                .isAfter(now.minusSeconds(security.getEmailSendIntervalSeconds()))) {
            log.info("验证邮件发送过于频繁，跳过。userId={}", user.getId());
            auditLogger.log("EMAIL_VERIFICATION_SKIPPED", user.getId(), user.getEmail(), ip, "RATE_LIMITED");
            return;
        }
        long sentIn24h = emailVerificationCodeRepository
                .countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(24)));
        if (sentIn24h >= security.getEmailDailyLimit()) {
            log.info("验证邮件超出 24 小时发送上限，跳过。userId={}", user.getId());
            auditLogger.log("EMAIL_VERIFICATION_SKIPPED", user.getId(), user.getEmail(), ip, "DAILY_LIMIT");
            return;
        }

        // 失效该用户此前未使用的验证码，保证同一时间只有一个有效码
        emailVerificationCodeRepository.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId())
                .ifPresent(previous -> previous.setUsedAt(now));

        String code = TokenUtils.generateNumericCode(VERIFICATION_CODE_LENGTH);
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setUserId(user.getId());
        entity.setCodeHash(TokenUtils.sha256Hex(code));
        entity.setEmail(user.getEmail());
        entity.setExpiresAt(now.plus(Duration.ofMinutes(security.getVerificationCodeTtlMinutes())));
        entity.setAttemptCount(0);
        emailVerificationCodeRepository.save(entity);

        mailService.sendVerificationEmail(user.getEmail(), user.getUsername(), code);
        auditLogger.log("EMAIL_VERIFICATION_SENT", user.getId(), user.getEmail(), ip, "SUCCESS");
    }

    // ==================== 邮箱验证 ====================

    /**
     * noRollbackFor = BusinessException：验证码错误的失败分支要先把 attemptCount +1
     * （以及过期/超限时置 usedAt）写进库再抛异常；默认回滚会让「错误次数上限」永远不生效，
     * 6 位验证码就等于可以无限次猜。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse verifyEmail(String rawEmail, String code, String ip, String userAgent) {
        String email = normalizeEmail(rawEmail);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.CODE_INVALID));

        if (user.isEmailVerified() && user.getStatus() != UserStatus.PENDING) {
            auditLogger.log("EMAIL_VERIFY_REJECTED", user.getId(), email, ip, "ALREADY_VERIFIED");
            throw new BusinessException(ErrorCode.CODE_INVALID, "该邮箱已完成验证，可直接登录");
        }

        EmailVerificationCode verificationCode = emailVerificationCodeRepository
                .findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CODE_INVALID));

        if (verificationCode.getExpiresAt().isBefore(Instant.now())) {
            verificationCode.setUsedAt(Instant.now());
            auditLogger.log("EMAIL_VERIFY_REJECTED", user.getId(), email, ip, "EXPIRED");
            throw new BusinessException(ErrorCode.CODE_EXPIRED);
        }

        var security = appProperties.getSecurity();
        if (verificationCode.getAttemptCount() >= security.getVerificationMaxAttempts()) {
            verificationCode.setUsedAt(Instant.now());
            auditLogger.log("EMAIL_VERIFY_REJECTED", user.getId(), email, ip, "TOO_MANY_ATTEMPTS");
            throw new BusinessException(ErrorCode.TOO_MANY_ATTEMPTS, "验证码错误次数过多，请重新获取");
        }

        if (!verificationCode.getCodeHash().equals(TokenUtils.sha256Hex(code))) {
            verificationCode.setAttemptCount(verificationCode.getAttemptCount() + 1);
            auditLogger.log("EMAIL_VERIFY_REJECTED", user.getId(), email, ip, "BAD_CODE");
            throw new BusinessException(ErrorCode.CODE_INVALID);
        }

        user.setEmailVerified(true);
        if (user.getStatus() == UserStatus.PENDING) {
            user.setStatus(UserStatus.ACTIVE);
        }
        verificationCode.setUsedAt(Instant.now());
        auditLogger.log("EMAIL_VERIFIED", user.getId(), email, ip, "SUCCESS");

        // 验证成功即自动登录：注册完成后直接进入首页，无需再手动登录
        String sessionId = newSessionId();
        String accessToken = jwtService.generateAccessToken(user, sessionId);
        String refreshToken = issueRefreshToken(user, ip, userAgent, sessionId);
        auditLogger.log("LOGIN_SUCCESS", user.getId(), user.getEmail(), ip, "AUTO_AFTER_VERIFY");
        return TokenResponse.of(accessToken, refreshToken, jwtService.getAccessTokenTtlSeconds());
    }

    @Transactional
    public String resendVerification(String rawEmail, String ip) {
        String email = normalizeEmail(rawEmail);
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getStatus() == UserStatus.PENDING && !user.isEmailVerified()) {
                issueVerificationCode(user, ip);
            } else {
                auditLogger.log("EMAIL_VERIFICATION_SKIPPED", user.getId(), email, ip, "ALREADY_ACTIVE");
            }
        });
        return RESEND_MESSAGE;
    }

    // ==================== 登录 ====================

    /**
     * noRollbackFor = BusinessException：密码错误时要先落库 failedLoginCount / lockedUntil
     * 再抛 INVALID_CREDENTIALS；默认回滚会让 DB 上的失败计数与锁定时间永远是初始值，
     * 账户锁定实际只靠内存计数（重启即失效）。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(LoginRequest request, String ip, String userAgent) {
        // 传输层解密：u = RC4 账号、p = RSA 密码，两者都带毫秒时间戳，
        // 校验不通过会抛 BAD_REQUEST / LOGIN_TIMEOUT，不会进入后续失败计数。
        LoginPayloadCipher.Credentials credentials = loginPayloadCipher.decrypt(request.u(), request.p());
        String account = credentials.account().trim();
        String password = credentials.password();

        if (account.isEmpty() || account.length() > MAX_ACCOUNT_LENGTH) {
            auditLogger.log("LOGIN_FAILURE", null, null, ip, "MALFORMED_ACCOUNT");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (loginAttemptService.isLocked(account, ip)) {
            auditLogger.log("LOGIN_BLOCKED", null, account, ip, "TOO_MANY_ATTEMPTS");
            throw new BusinessException(ErrorCode.TOO_MANY_ATTEMPTS);
        }

        Optional<User> userOpt = findByAccount(account);
        if (userOpt.isEmpty()) {
            // 不存在的账号同样计数，避免通过“是否被锁”枚举账号存在性
            loginAttemptService.recordFailure(account, ip);
            auditLogger.log("LOGIN_FAILURE", null, account, ip, "USER_NOT_FOUND");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptService.recordFailure(account, ip);
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            if (loginAttemptService.isLocked(account, ip)) {
                user.setLockedUntil(Instant.now()
                        .plus(Duration.ofMinutes(appProperties.getSecurity().getLoginLockMinutes())));
                auditLogger.log("LOGIN_LOCKED", user.getId(), account, ip,
                        "LOCKED_" + appProperties.getSecurity().getLoginLockMinutes() + "M");
            } else {
                auditLogger.log("LOGIN_FAILURE", user.getId(), account, ip, "BAD_CREDENTIALS");
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!user.isEmailVerified() || user.getStatus() == UserStatus.PENDING) {
            auditLogger.log("LOGIN_FAILURE", user.getId(), account, ip, "EMAIL_NOT_VERIFIED");
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            // 管理员禁用：先于锁定判断，且不会自动恢复（LOCKED 到期会自己解开）
            auditLogger.log("LOGIN_BLOCKED", user.getId(), account, ip, "ACCOUNT_DISABLED");
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (user.getStatus() == UserStatus.LOCKED
                || (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now()))) {
            auditLogger.log("LOGIN_BLOCKED", user.getId(), account, ip, "ACCOUNT_LOCKED");
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        loginAttemptService.clear(account, ip);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);

        String sessionId = newSessionId();
        String accessToken = jwtService.generateAccessToken(user, sessionId);
        String refreshToken = issueRefreshToken(user, ip, userAgent, sessionId);
        log.info("用户登录成功 userId={}, username={}, ip={}, ua={}",
                user.getId(), user.getUsername(), ip, userAgent);
        auditLogger.log("LOGIN_SUCCESS", user.getId(), account, ip, "SUCCESS");
        return TokenResponse.of(accessToken, refreshToken, jwtService.getAccessTokenTtlSeconds());
    }

    private Optional<User> findByAccount(String account) {
        return userRepository.findByEmail(account.toLowerCase(Locale.ROOT))
                .or(() -> userRepository.findByUsername(account));
    }

    /** 生成本次登录的会话标识，随 access token 的 sid 声明一同下发。 */
    private String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 创建登录态（refresh token），并记录登录设备信息。
     *
     * @param sessionId 本次登录会话标识，与 access token 的 sid 声明一致
     */
    private String issueRefreshToken(User user, String ip, String userAgent, String sessionId) {
        String token = TokenUtils.generateToken();
        Instant now = Instant.now();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setSessionId(sessionId);
        refreshToken.setTokenHash(TokenUtils.sha256Hex(token));
        refreshToken.setDeviceName(DeviceUtils.parseDeviceName(userAgent));
        refreshToken.setUserAgent(truncate(userAgent, MAX_USER_AGENT_LENGTH));
        refreshToken.setIp(ip);
        refreshToken.setLastUsedAt(now);
        refreshToken.setExpiresAt(now.plusSeconds(jwtService.getRefreshTokenTtlSeconds()));
        refreshTokenRepository.save(refreshToken);
        return token;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    // ==================== 刷新 / 登出 ====================

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(TokenUtils.sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录"));
        if (refreshToken.getRevokedAt() != null || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录"));
        if (user.getStatus() == UserStatus.DISABLED) {
            // 单独判一次：否则会落到下面的 EMAIL_NOT_VERIFIED，提示词完全对不上
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!user.isEmailVerified() || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        // 刷新即视为一次活跃，用于「最近活跃时间」展示
        refreshToken.setLastUsedAt(Instant.now());
        String accessToken = jwtService.generateAccessToken(user, refreshToken.getSessionId());
        return TokenResponse.of(accessToken, rawRefreshToken, jwtService.getAccessTokenTtlSeconds());
    }

    @Transactional
    public void logout(Long userId, String rawRefreshToken, String ip) {
        refreshTokenRepository.findByTokenHash(TokenUtils.sha256Hex(rawRefreshToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> token.setRevokedAt(Instant.now()));
        auditLogger.log("LOGOUT", userId, null, ip, "SUCCESS");
    }

    // ==================== 登录设备 ====================

    /**
     * 查询当前账号的有效登录设备（未撤销且未过期），标记出当前设备，
     * 并顺带补全当前设备的名称 / IP / User-Agent（兼容本次改动前创建的登录态）。
     *
     * @param currentSessionId    当前 access token 中的会话标识（新登录态才有），可为空
     * @param currentRefreshToken 当前浏览器持有的 refresh token，用于兜底识别当前设备，可为空
     */
    @Transactional
    public List<DeviceInfoResponse> listDevices(Long userId, String currentSessionId, String currentRefreshToken,
                                                String ip, String userAgent) {
        Instant now = Instant.now();
        List<RefreshToken> tokens = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(userId, now);

        Long currentId = resolveCurrentDeviceId(userId, currentSessionId, currentRefreshToken);
        if (currentId != null) {
            tokens.stream()
                    .filter(token -> currentId.equals(token.getId()))
                    .findFirst()
                    .ifPresent(token -> backfillDeviceInfo(token, ip, userAgent, now));
        }
        return tokens.stream().map(token -> toDeviceInfo(token, currentId)).toList();
    }

    /**
     * 将指定设备下线（撤销其登录态）。仅能操作当前账号名下的设备。
     */
    @Transactional
    public void revokeDevice(Long userId, Long deviceId, String ip) {
        RefreshToken token = refreshTokenRepository.findById(deviceId)
                .filter(item -> item.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "设备不存在或已下线"));
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
        }
        auditLogger.log("DEVICE_REVOKED", userId, null, ip, "SUCCESS");
    }

    /**
     * 下线该账号除当前设备外的所有设备。
     *
     * @return 被下线的设备数量
     */
    @Transactional
    public int revokeOtherDevices(Long userId, String currentSessionId, String currentRefreshToken, String ip) {
        Instant now = Instant.now();
        Long currentId = resolveCurrentDeviceId(userId, currentSessionId, currentRefreshToken);
        if (currentId == null) {
            // 认不出当前设备时不能「全部下线」——那会把当前设备一起踢掉，
            // 与「下线其它设备」的语义相反，用户会莫名其妙被登出
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无法识别当前设备，请重新登录后再操作");
        }
        int revoked = refreshTokenRepository.revokeAllExceptId(userId, currentId, now);
        auditLogger.log("DEVICES_REVOKED_OTHERS", userId, null, ip, "COUNT_" + revoked);
        return revoked;
    }

    /**
     * 识别「当前设备」：优先用 access token 的会话标识，其次用浏览器携带的 refresh token 兜底，
     * 以便本次改动前创建的旧登录态同样能被识别。
     *
     * @return 当前设备对应的登录态记录 ID，识别不到时返回 null
     */
    private Long resolveCurrentDeviceId(Long userId, String currentSessionId, String currentRefreshToken) {
        if (currentSessionId != null && !currentSessionId.isBlank()) {
            Optional<RefreshToken> bySession = refreshTokenRepository
                    .findFirstByUserIdAndSessionIdAndRevokedAtIsNull(userId, currentSessionId);
            if (bySession.isPresent()) {
                return bySession.get().getId();
            }
        }
        if (currentRefreshToken != null && !currentRefreshToken.isBlank()) {
            return refreshTokenRepository.findByTokenHash(TokenUtils.sha256Hex(currentRefreshToken))
                    .filter(token -> token.getUserId().equals(userId) && token.getRevokedAt() == null)
                    .map(RefreshToken::getId)
                    .orElse(null);
        }
        return null;
    }

    /** 补全设备信息并刷新活跃时间；已存在的字段不覆盖。 */
    private void backfillDeviceInfo(RefreshToken token, String ip, String userAgent, Instant now) {
        if (token.getDeviceName() == null || token.getDeviceName().isBlank()) {
            token.setDeviceName(DeviceUtils.parseDeviceName(userAgent));
        }
        if (token.getUserAgent() == null || token.getUserAgent().isBlank()) {
            token.setUserAgent(truncate(userAgent, MAX_USER_AGENT_LENGTH));
        }
        if (token.getIp() == null || token.getIp().isBlank()) {
            token.setIp(ip);
        }
        token.setLastUsedAt(now);
    }

    private DeviceInfoResponse toDeviceInfo(RefreshToken token, Long currentDeviceId) {
        boolean current = currentDeviceId != null && currentDeviceId.equals(token.getId());
        String deviceName = token.getDeviceName();
        return new DeviceInfoResponse(
                token.getId(),
                deviceName == null || deviceName.isBlank() ? "未知设备" : deviceName,
                token.getUserAgent(),
                token.getIp(),
                current,
                token.getCreatedAt(),
                token.getLastUsedAt(),
                token.getExpiresAt());
    }

    // ==================== 找回密码 ====================

    @Transactional
    public String forgotPassword(String rawEmail, String ip, String frontendOrigin) {
        String email = normalizeEmail(rawEmail);
        userRepository.findByEmail(email).ifPresent(user -> issuePasswordResetToken(user, ip, frontendOrigin));
        // 防枚举：无论邮箱是否存在，响应一致
        return FORGOT_MESSAGE;
    }

    private void issuePasswordResetToken(User user, String ip, String frontendOrigin) {
        var security = appProperties.getSecurity();
        Instant now = Instant.now();

        Optional<PasswordResetToken> latest =
                passwordResetTokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId());
        if (latest.isPresent() && latest.get().getCreatedAt()
                .isAfter(now.minusSeconds(security.getEmailSendIntervalSeconds()))) {
            auditLogger.log("PASSWORD_RESET_SKIPPED", user.getId(), user.getEmail(), ip, "RATE_LIMITED");
            return;
        }
        long sentIn24h = passwordResetTokenRepository
                .countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(24)));
        if (sentIn24h >= security.getEmailDailyLimit()) {
            auditLogger.log("PASSWORD_RESET_SKIPPED", user.getId(), user.getEmail(), ip, "DAILY_LIMIT");
            return;
        }

        String token = TokenUtils.generateToken();
        PasswordResetToken entity = new PasswordResetToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(TokenUtils.sha256Hex(token));
        entity.setExpiresAt(now.plus(Duration.ofMinutes(security.getResetTokenTtlMinutes())));
        entity.setRequestIp(ip);
        passwordResetTokenRepository.save(entity);

        mailService.sendPasswordResetEmail(user.getEmail(), user.getUsername(), token, frontendOrigin);
        auditLogger.log("PASSWORD_RESET_REQUESTED", user.getId(), user.getEmail(), ip, "SUCCESS");
    }

    @Transactional
    public void resetPassword(String token, String newPassword, String ip) {
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHash(TokenUtils.sha256Hex(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "重置链接无效或已过期"));

        if (resetToken.getUsedAt() != null) {
            auditLogger.log("PASSWORD_RESET_REJECTED", resetToken.getUserId(), null, ip, "ALREADY_USED");
            throw new BusinessException(ErrorCode.TOKEN_USED, "该重置链接已被使用，请重新申请");
        }
        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            auditLogger.log("PASSWORD_RESET_REJECTED", resetToken.getUserId(), null, ip, "EXPIRED");
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "重置链接已过期，请重新申请");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "重置链接无效或已过期"));

        applyNewPassword(user, newPassword);
        resetToken.setUsedAt(Instant.now());
        auditLogger.log("PASSWORD_RESET", user.getId(), user.getEmail(), ip, "SUCCESS");
    }

    /**
     * 校验重置链接 token 是否有效，返回脱敏邮箱与过期时间（供前端展示）。
     */
    @Transactional(readOnly = true)
    public ResetTokenInfoResponse checkResetToken(String token) {
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHash(TokenUtils.sha256Hex(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "重置链接无效或已过期"));

        if (resetToken.getUsedAt() != null) {
            throw new BusinessException(ErrorCode.TOKEN_USED, "该重置链接已被使用，请重新申请");
        }
        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "重置链接已过期，请重新申请");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "重置链接无效或已过期"));
        return new ResetTokenInfoResponse(AuditLogger.maskEmail(user.getEmail()),
                resetToken.getExpiresAt().toString());
    }

    // ==================== 凭原密码修改密码 ====================

    /**
     * 登录状态下修改密码：先校验原密码，再设置新密码并撤销所有登录态。
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword, String ip) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            auditLogger.log("PASSWORD_CHANGE_REJECTED", user.getId(), user.getEmail(), ip, "BAD_OLD_PASSWORD");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "原密码错误，请重新输入");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }

        applyNewPassword(user, newPassword);
        auditLogger.log("PASSWORD_CHANGED", user.getId(), user.getEmail(), ip, "SUCCESS");
    }

    // ==================== 找回密码（验证码方式） ====================

    /**
     * 发送「重置密码」验证码（前端输入验证码即可直接改密）。
     */
    @Transactional
    public String sendPasswordResetCode(String rawEmail, String ip) {
        String email = normalizeEmail(rawEmail);
        userRepository.findByEmail(email).ifPresent(user -> issuePasswordResetCode(user, ip));
        // 防枚举：无论邮箱是否存在，响应一致
        return RESET_CODE_MESSAGE;
    }

    private void issuePasswordResetCode(User user, String ip) {
        var security = appProperties.getSecurity();
        Instant now = Instant.now();

        Optional<PasswordResetCode> latest =
                passwordResetCodeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId());
        if (latest.isPresent() && latest.get().getCreatedAt()
                .isAfter(now.minusSeconds(security.getEmailSendIntervalSeconds()))) {
            auditLogger.log("PASSWORD_RESET_CODE_SKIPPED", user.getId(), user.getEmail(), ip, "RATE_LIMITED");
            return;
        }
        long sentIn24h = passwordResetCodeRepository
                .countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(24)));
        if (sentIn24h >= security.getEmailDailyLimit()) {
            auditLogger.log("PASSWORD_RESET_CODE_SKIPPED", user.getId(), user.getEmail(), ip, "DAILY_LIMIT");
            return;
        }

        // 使旧码失效，保证同一时间只有一个有效码
        passwordResetCodeRepository.findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId())
                .ifPresent(previous -> previous.setUsedAt(now));

        String code = TokenUtils.generateNumericCode(VERIFICATION_CODE_LENGTH);
        PasswordResetCode entity = new PasswordResetCode();
        entity.setUserId(user.getId());
        entity.setCodeHash(TokenUtils.sha256Hex(code));
        entity.setExpiresAt(now.plus(Duration.ofMinutes(security.getResetCodeTtlMinutes())));
        entity.setAttemptCount(0);
        entity.setRequestIp(ip);
        passwordResetCodeRepository.save(entity);

        mailService.sendPasswordResetCodeEmail(user.getEmail(), user.getUsername(), code);
        auditLogger.log("PASSWORD_RESET_CODE_SENT", user.getId(), user.getEmail(), ip, "SUCCESS");
    }

    /**
     * 校验邮箱验证码并重置密码。
     */
    /** noRollbackFor 同 verifyEmail：错误次数与 usedAt 必须落库，否则重置码可无限次爆破。 */
    @Transactional(noRollbackFor = BusinessException.class)
    public void resetPasswordByCode(String rawEmail, String code, String newPassword, String ip) {
        String email = normalizeEmail(rawEmail);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.CODE_INVALID));

        PasswordResetCode resetCode = passwordResetCodeRepository
                .findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CODE_INVALID));

        if (resetCode.getExpiresAt().isBefore(Instant.now())) {
            resetCode.setUsedAt(Instant.now());
            auditLogger.log("PASSWORD_RESET_CODE_REJECTED", user.getId(), email, ip, "EXPIRED");
            throw new BusinessException(ErrorCode.CODE_EXPIRED);
        }

        var security = appProperties.getSecurity();
        if (resetCode.getAttemptCount() >= security.getResetCodeMaxAttempts()) {
            resetCode.setUsedAt(Instant.now());
            auditLogger.log("PASSWORD_RESET_CODE_REJECTED", user.getId(), email, ip, "TOO_MANY_ATTEMPTS");
            throw new BusinessException(ErrorCode.TOO_MANY_ATTEMPTS, "验证码错误次数过多，请重新获取");
        }

        if (!resetCode.getCodeHash().equals(TokenUtils.sha256Hex(code))) {
            resetCode.setAttemptCount(resetCode.getAttemptCount() + 1);
            auditLogger.log("PASSWORD_RESET_CODE_REJECTED", user.getId(), email, ip, "BAD_CODE");
            throw new BusinessException(ErrorCode.CODE_INVALID);
        }

        applyNewPassword(user, newPassword);
        resetCode.setUsedAt(Instant.now());
        auditLogger.log("PASSWORD_RESET", user.getId(), email, ip, "SUCCESS");
    }

    /** 统一设置新密码并撤销所有登录态。 */
    private void applyNewPassword(User user, String newPassword) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        int revoked = refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
        log.info("密码重置成功 userId={}, 已撤销 refresh token 数量={}", user.getId(), revoked);
    }

    // ==================== 当前用户 ====================

    @Transactional(readOnly = true)
    public UserInfoResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return toUserInfo(user);
    }

    // ==================== 头像 ====================

    /**
     * 更新用户头像：保存新文件 → 更新记录 → 删除旧文件。
     */
    @Transactional
    public UserInfoResponse updateAvatar(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        String oldUrl = user.getAvatarUrl();
        String newUrl = fileStorageService.storeAvatar(file);
        user.setAvatarUrl(newUrl);
        if (oldUrl != null && !oldUrl.equals(newUrl)) {
            fileStorageService.deleteByUrl(oldUrl);
        }
        auditLogger.log("AVATAR_UPDATED", user.getId(), user.getEmail(), null, "SUCCESS");
        return toUserInfo(user);
    }

    private UserInfoResponse toUserInfo(User user) {
        return new UserInfoResponse(user.getId(), user.getUsername(), user.getEmail(), user.getAvatarUrl(),
                user.isEmailVerified(), user.getStatus().name(), user.isAdmin(), user.getCreatedAt());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
