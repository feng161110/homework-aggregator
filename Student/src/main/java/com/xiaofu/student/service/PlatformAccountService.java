//平台凭证业务：入库、校验、Cookie 失效后自动重登
package com.xiaofu.student.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.dto.PlatformBindRequest;
import com.xiaofu.student.dto.PlatformCheckResponse;
import com.xiaofu.student.dto.PlatformCookieRequest;
import com.xiaofu.student.dto.PlatformCoursesResponse;
import com.xiaofu.student.dto.PlatformCredentialResponse;
import com.xiaofu.student.dto.PlatformLinksResponse;
import com.xiaofu.student.dto.PlatformLoginSecretResponse;
import com.xiaofu.student.dto.PlatformRefreshResponse;
import com.xiaofu.student.dto.PlatformStatusResponse;
import com.xiaofu.student.entity.PlatformAccount;
import com.xiaofu.student.entity.PlatformCookieStatus;
import com.xiaofu.student.entity.PlatformType;
import com.xiaofu.student.repository.PlatformAccountRepository;
import com.xiaofu.student.util.PlatformCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台凭证全部落在 platform_accounts 表：
 * 账号 + AES-256-GCM 加密密码 + Cookie（JSON）。
 * <p>
 * 取数的默认姿势是 {@link #check} 用库里的 Cookie；
 * Cookie 失效时调 {@link #refresh} 用库里的账号密码自动重登并覆盖 Cookie；
 * 学习通无法自动重登，返回 needManual 让前端引导用户手动获取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAccountService {

    private static final String SOURCE_PASSWORD = "password";
    private static final String SOURCE_PLUGIN = "plugin";

    /** 目前提供课程列表接口的平台 */
    private static final Set<PlatformType> COURSE_PLATFORMS =
            Set.of(PlatformType.XUEXITONG, PlatformType.YUKETANG, PlatformType.ZHIHUISHU);

    /** 目前提供作业 / 考试链接接口的平台 */
    private static final Set<PlatformType> LINK_PLATFORMS =
            Set.of(PlatformType.XUEXITONG, PlatformType.ZHIHUISHU);

    private final PlatformAccountRepository platformAccountRepository;
    private final PlatformClient platformClient;
    private final PlatformCrypto platformCrypto;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    // ==================== 查询 ====================

    /** 三个平台的绑定状态（未绑定的也返回，bound=false）。 */
    @Transactional(readOnly = true)
    public List<PlatformStatusResponse> list(Long userId) {
        Map<String, PlatformAccount> owned = platformAccountRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(PlatformAccount::getPlatform, Function.identity(), (a, b) -> a));
        return Arrays.stream(PlatformType.values())
                .map(type -> toStatus(type, owned.get(type.getKey())))
                .toList();
    }

    /** 单个平台的凭证详情（含 Cookie，不含密码明文）。 */
    @Transactional(readOnly = true)
    public PlatformCredentialResponse detail(Long userId, PlatformType type) {
        return toCredential(type, find(userId, type));
    }

    // ==================== 写入 ====================

    /** 前端用账号密码登录成功后回传凭证：加密保存密码 + 入库 Cookie。 */
    @Transactional
    public PlatformCredentialResponse bind(Long userId, PlatformType type, PlatformBindRequest request) {
        PlatformAccount entity = findOrCreate(userId, type);

        entity.setAccount(blankToNull(request.account()));
        if (request.password() != null && !request.password().isBlank()) {
            entity.setPasswordCipher(platformCrypto.encrypt(request.password()));
        }
        String source = blankToNull(request.source()) == null ? SOURCE_PASSWORD : request.source().trim();
        applyCookies(entity, request.cookies(), request.extra(), source);
        entity.setLastLoginAt(Instant.now());

        PlatformAccount saved = platformAccountRepository.save(entity);
        auditLogger.log("PLATFORM_BOUND", userId, saved.getAccount(), null, type.getKey() + ":" + source);
        return toCredential(type, saved);
    }

    /**
     * 只回传 Cookie（学习通浏览器助手 / 手动粘贴）：不存在则创建，保存后立即校验一次。
     * <p>
     * 学习通的助手路径没有账号密码，首次使用时库里必然没有记录，
     * 因此这里必须允许创建；若沿用「必须已绑定」会与「未绑定才自动同步」形成死锁。
     */
    @Transactional
    public PlatformCredentialResponse saveCookies(Long userId, PlatformType type, PlatformCookieRequest request) {
        Map<String, String> cookies = request.cookies() == null ? Map.of() : request.cookies();
        if (cookies.isEmpty()) {
            // 空 Cookie 会造出一条「看起来已绑定」的废记录，进而把前端的表单入口藏起来
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "未获取到" + type.getDisplayName() + " Cookie，请先在浏览器里登录该平台");
        }

        PlatformAccount entity = findOrCreate(userId, type);
        String account = blankToNull(request.account());
        if (account != null) {
            entity.setAccount(account);
        }
        applyCookies(entity, cookies, request.extra(),
                blankToNull(request.source()) == null ? SOURCE_PLUGIN : request.source().trim());
        entity.setLastLoginAt(Instant.now());

        try {
            PlatformClient.CheckResult result = platformClient.check(type, cookiesOf(entity), extraOf(entity),
                    entity.getAccount());
            entity.setCookieStatus(result.valid() ? PlatformCookieStatus.VALID : PlatformCookieStatus.EXPIRED);
            entity.setLastCheckedAt(Instant.now());
        } catch (BusinessException e) {
            // 平台服务不可用时不阻断保存，状态保留 UNKNOWN
            log.warn("保存 Cookie 后校验失败 platform={} err={}", type.getKey(), e.getMessage());
        }

        // account 只接受调用方显式传入的值：平台返回的是姓名/昵称，不能冒充登录账号，
        // 拿不到就如实留空，不允许编造。
        PlatformAccount saved = platformAccountRepository.save(entity);
        auditLogger.log("PLATFORM_COOKIE_SAVED", userId, saved.getAccount(), null,
                type.getKey() + ":" + saved.getCookieStatus());
        return toCredential(type, saved);
    }

    /** 解绑。 */
    @Transactional
    public void unbind(Long userId, PlatformType type) {
        platformAccountRepository.findByUserIdAndPlatform(userId, type.getKey()).ifPresent(entity -> {
            auditLogger.log("PLATFORM_UNBOUND", userId, entity.getAccount(), null, type.getKey());
            platformAccountRepository.delete(entity);
        });
    }

    // ==================== 密码下发（仅学习通） ====================

    /**
     * 取平台账号与明文密码，供前端调用浏览器助手自动重新登录时一次性使用。
     * <p>
     * 安全边界：
     * <ul>
     *   <li>只对学习通开放 —— 其余平台的自动重登由后端直连登录服务完成，无需把密码交给前端</li>
     *   <li>必须已登录，且只能取到自己的记录（userId 来自 JWT，不接受前端传入）</li>
     *   <li>解密失败（密钥已更换）时密码按 null 返回，让前端转人工输入</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public PlatformLoginSecretResponse secret(Long userId, PlatformType type) {
        if (type != PlatformType.XUEXITONG) {
            throw new BusinessException(ErrorCode.PLATFORM_NOT_SUPPORTED,
                    type.getDisplayName() + "不支持在前端取用密码，请用「校验凭证」触发后端自动重登");
        }
        PlatformAccount entity = find(userId, type);
        if (entity == null) {
            return new PlatformLoginSecretResponse(type.getKey(), false, null, null);
        }
        return new PlatformLoginSecretResponse(type.getKey(), true,
                entity.getAccount(), safeDecrypt(entity.getPasswordCipher()));
    }

    // ==================== 校验 / 自动重登 ====================

    /**
     * 用库里保存的 Cookie 调平台接口校验，并回写状态。
     * 未绑定时不抛异常，返回 valid=false + 引导文案（前端据此显示「重新获取 Cookie」）。
     */
    @Transactional
    public PlatformCheckResponse check(Long userId, PlatformType type) {
        PlatformAccount entity = find(userId, type);
        if (entity == null) {
            auditLogger.log("PLATFORM_CHECK_UNBOUND", userId, null, null, type.getKey());
            return new PlatformCheckResponse(false, notBoundMessage(type), null, null,
                    PlatformCookieStatus.UNKNOWN.name(), null);
        }
        PlatformClient.CheckResult result = platformClient.check(type, cookiesOf(entity), extraOf(entity),
                entity.getAccount());

        entity.setCookieStatus(result.valid() ? PlatformCookieStatus.VALID : PlatformCookieStatus.EXPIRED);
        entity.setLastCheckedAt(Instant.now());
        platformAccountRepository.save(entity);

        if (!result.valid()) {
            auditLogger.log("PLATFORM_COOKIE_EXPIRED", userId, entity.getAccount(), null, type.getKey());
        }
        return new PlatformCheckResponse(result.valid(), result.message(), result.name(), result.avatar(),
                entity.getCookieStatus().name(), result.userinfo());
    }

    /**
     * Cookie 失效后自动重新获取：用库里保存的「账号 + 密码」调平台登录服务换一份新 Cookie。
     * <p>
     * 三种「自动不行」的情况统一返回 needManual=true（前端据此提示人工输入）：
     * <ul>
     *   <li>还没绑定过 —— 先获取一次再说</li>
     *   <li>库里没保存账号或密码 —— 只能人工输入</li>
     *   <li>自动登录被平台拒绝（如需滑块验证码）或登录服务未启动 —— 转人工</li>
     * </ul>
     */
    @Transactional
    public PlatformRefreshResponse refresh(Long userId, PlatformType type) {
        PlatformAccount entity = find(userId, type);
        if (entity == null) {
            auditLogger.log("PLATFORM_REFRESH_MANUAL", userId, null, null, type.getKey());
            return new PlatformRefreshResponse(false, true, notBoundMessage(type), null,
                    PlatformCookieStatus.UNKNOWN.name());
        }

        String account = blankToNull(entity.getAccount());
        String password = safeDecrypt(entity.getPasswordCipher());
        if (account == null || password == null) {
            auditLogger.log("PLATFORM_REFRESH_MANUAL", userId, account, null,
                    type.getKey() + ":NO_CREDENTIAL");
            return new PlatformRefreshResponse(false, true, noCredentialMessage(type), null,
                    entity.getCookieStatus().name());
        }

        PlatformClient.LoginResult result;
        try {
            result = platformClient.login(type, account, password);
        } catch (BusinessException e) {
            // 平台登录服务不可用时不抛给前端，转为人工提示，避免界面只弹一个 500
            log.warn("自动重新登录失败 platform={} err={}", type.getKey(), e.getMessage());
            auditLogger.log("PLATFORM_REFRESH_FAILED", userId, account, null,
                    type.getKey() + ":SERVICE_UNAVAILABLE");
            return new PlatformRefreshResponse(false, true,
                    "自动重新登录失败：" + e.getMessage(), null, entity.getCookieStatus().name());
        }

        if (!result.ok()) {
            entity.setCookieStatus(PlatformCookieStatus.EXPIRED);
            entity.setLastCheckedAt(Instant.now());
            platformAccountRepository.save(entity);
            auditLogger.log("PLATFORM_REFRESH_FAILED", userId, account, null, type.getKey());
            return new PlatformRefreshResponse(false, true,
                    "自动重新登录失败：" + result.message() + "，" + manualHint(type), null,
                    entity.getCookieStatus().name());
        }

        applyCookies(entity, result.cookies(), result.extra(), SOURCE_PASSWORD);
        entity.setLastLoginAt(Instant.now());
        entity.setLastCheckedAt(Instant.now());
        PlatformAccount saved = platformAccountRepository.save(entity);
        auditLogger.log("PLATFORM_REFRESHED", userId, saved.getAccount(), null, type.getKey());
        return new PlatformRefreshResponse(true, false, "Cookie 已失效，已用保存的账号密码自动重新登录",
                result.name(), saved.getCookieStatus().name());
    }

    // ==================== 作业 / 考试链接（学习通 / 智慧树） ====================

    /**
     * 取平台的作业 / 考试链接。
     * <p>
     * 未绑定或 Cookie 为空时不抛异常，返回 success=false + 引导文案，
     * 让前端沿用「先获取 Cookie」的提示，而不是弹一个 500。
     */
    @Transactional(readOnly = true)
    public PlatformLinksResponse desktopLinks(Long userId, PlatformType type) {
        if (!LINK_PLATFORMS.contains(type)) {
            throw new BusinessException(ErrorCode.PLATFORM_NOT_SUPPORTED,
                    type.getDisplayName() + "暂未提供作业 / 考试链接接口");
        }
        PlatformAccount entity = find(userId, type);
        if (entity == null) {
            return new PlatformLinksResponse(false, notBoundMessage(type), List.of());
        }
        Map<String, String> cookies = cookiesOf(entity);
        if (cookies.isEmpty()) {
            return new PlatformLinksResponse(false, notBoundMessage(type), List.of());
        }

        PlatformClient.DesktopLinksResult result = platformClient.desktopLinks(type, cookies);
        auditLogger.log("PLATFORM_DESKTOP_LINKS", userId, entity.getAccount(), null,
                type.getKey() + ":" + (result.ok() ? "OK" : "FAILED"));
        return new PlatformLinksResponse(result.ok(), result.message(), toCourseGroups(result.courses()));
    }

    private static List<PlatformLinksResponse.CourseGroup> toCourseGroups(List<PlatformClient.CourseLinks> courses) {
        return courses.stream()
                .map(course -> new PlatformLinksResponse.CourseGroup(
                        course.courseId(), course.courseName(),
                        course.items().stream()
                                .map(item -> new PlatformLinksResponse.LinkItem(item.id(), item.title(),
                                        item.type(), item.url(), item.mobileUrl(), item.expired(),
                                        item.status(), item.statusClass(), item.remainText(),
                                        item.startTime(), item.endTime()))
                                .toList()))
                .toList();
    }

    // ==================== 课程列表（学习通 / 雨课堂 / 智慧树） ====================

    /**
     * 取平台的课程列表（学习通 / 雨课堂 / 智慧树）。学习通还带每门课的 pageHeader 入口。
     * <p>
     * 未绑定或 Cookie 为空时不抛异常，返回 success=false + 引导文案，前端据此提示先绑定。
     */
    @Transactional(readOnly = true)
    public PlatformCoursesResponse courses(Long userId, PlatformType type) {
        if (!COURSE_PLATFORMS.contains(type)) {
            throw new BusinessException(ErrorCode.PLATFORM_NOT_SUPPORTED,
                    type.getDisplayName() + "暂未提供课程列表接口");
        }
        PlatformAccount entity = find(userId, type);
        if (entity == null) {
            return new PlatformCoursesResponse(false, notBoundMessage(type), 0, List.of());
        }
        Map<String, String> cookies = cookiesOf(entity);
        if (cookies.isEmpty()) {
            return new PlatformCoursesResponse(false, notBoundMessage(type), 0, List.of());
        }

        PlatformClient.CourseListResult result = platformClient.courses(type, cookies);
        auditLogger.log("PLATFORM_COURSES", userId, entity.getAccount(), null,
                type.getKey() + ":" + (result.ok() ? "OK" : "FAILED"));
        return new PlatformCoursesResponse(result.ok(), result.message(),
                result.courses().size(), toCourses(result.courses()));
    }

    private static List<PlatformCoursesResponse.Course> toCourses(List<PlatformClient.CourseItem> courses) {
        return courses.stream()
                .map(course -> new PlatformCoursesResponse.Course(
                        course.courseName(), course.courseId(), course.clazzId(), course.url(),
                        course.teacher(), course.description(), course.coverUrl(),
                        course.status(), course.statusText(),
                        course.pages().stream()
                                .map(page -> new PlatformCoursesResponse.PageLink(
                                        page.key(), page.title(), page.url()))
                                .toList(),
                        course.teacherAvatar(), course.schoolLogo(), course.schoolName(),
                        course.term(), course.studentsCount(), course.progress()))
                .toList();
    }

    // ==================== 内部工具 ====================

    private PlatformAccount find(Long userId, PlatformType type) {
        return platformAccountRepository.findByUserIdAndPlatform(userId, type.getKey()).orElse(null);
    }

    /**
     * 取绑定记录，不存在则新建（不落库，由调用方 save）。
     * 一个用户 + 一个平台只有一条记录（uk_user_platform）。
     */
    private PlatformAccount findOrCreate(Long userId, PlatformType type) {
        return platformAccountRepository.findByUserIdAndPlatform(userId, type.getKey())
                .orElseGet(() -> {
                    PlatformAccount created = new PlatformAccount();
                    created.setUserId(userId);
                    created.setPlatform(type.getKey());
                    return created;
                });
    }

    /** 未绑定时的统一引导文案（与前端「重新获取 Cookie」按钮对应）。 */
    private static String notBoundMessage(PlatformType type) {
        return type.getDisplayName() + "尚未绑定账号，请在浏览器里登录" + type.getDisplayName()
                + "后点「重新获取 Cookie」，或填写账号密码手动获取。";
    }

    /** 库里没有保存账号或密码时的引导文案：只能人工输入。 */
    private static String noCredentialMessage(PlatformType type) {
        return "数据库中没有保存" + type.getDisplayName() + "的账号或密码，无法自动获取 Cookie，"
                + "请手动输入账号密码后重试。";
    }

    /** 自动登录失败时的补充引导。 */
    private static String manualHint(PlatformType type) {
        return type == PlatformType.XUEXITONG
                ? "请在浏览器登录学习通后点「重新获取 Cookie」，或手动输入账号密码"
                : "请手动输入账号密码重新登录";
    }

    private void applyCookies(PlatformAccount entity, Map<String, String> cookies,
                              Map<String, Object> extra, String source) {
        Map<String, String> safeCookies = cookies == null ? Map.of() : cookies;
        if (safeCookies.isEmpty()) {
            // 上游没回传 Cookie 时不要用空集覆盖库里已有的凭证：
            // 一次失败的重登会把账号彻底打哑，只能靠用户重新登录才能恢复
            entity.setCookieStatus(PlatformCookieStatus.UNKNOWN);
            return;
        }
        entity.setCookiesJson(writeJson(safeCookies));
        entity.setExtraJson(extra == null || extra.isEmpty() ? null : writeJson(extra));
        entity.setCookieSource(source);
        entity.setCookieStatus(PlatformCookieStatus.VALID);
    }

    private Map<String, String> cookiesOf(PlatformAccount entity) {
        Map<String, String> map = readJson(entity.getCookiesJson(), new TypeReference<>() {
        });
        return map == null ? new LinkedHashMap<>() : map;
    }

    private Map<String, Object> extraOf(PlatformAccount entity) {
        Map<String, Object> map = readJson(entity.getExtraJson(), new TypeReference<>() {
        });
        return map == null ? new LinkedHashMap<>() : map;
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("平台凭证 JSON 解析失败，按空处理: {}", e.getMessage());
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "平台凭证序列化失败");
        }
    }

    private String safeDecrypt(String cipher) {
        try {
            return platformCrypto.decrypt(cipher);
        } catch (BusinessException e) {
            log.warn("平台密码解密失败（加密密钥可能已更换），需要重新绑定");
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private PlatformStatusResponse toStatus(PlatformType type, PlatformAccount entity) {
        if (entity == null) {
            return new PlatformStatusResponse(type.getKey(), type.getDisplayName(), false, null, false,
                    PlatformCookieStatus.UNKNOWN.name(), null, 0, null, null);
        }
        return new PlatformStatusResponse(type.getKey(), type.getDisplayName(), true, entity.getAccount(),
                entity.getPasswordCipher() != null, entity.getCookieStatus().name(), entity.getCookieSource(),
                cookiesOf(entity).size(), entity.getLastLoginAt(), entity.getLastCheckedAt());
    }

    private PlatformCredentialResponse toCredential(PlatformType type, PlatformAccount entity) {
        if (entity == null) {
            return new PlatformCredentialResponse(type.getKey(), type.getDisplayName(), false, null, false,
                    PlatformCookieStatus.UNKNOWN.name(), null, Map.of(), Map.of(), null, null);
        }
        return new PlatformCredentialResponse(type.getKey(), type.getDisplayName(), true, entity.getAccount(),
                entity.getPasswordCipher() != null, entity.getCookieStatus().name(), entity.getCookieSource(),
                cookiesOf(entity), extraOf(entity), entity.getLastLoginAt(), entity.getLastCheckedAt());
    }
}
