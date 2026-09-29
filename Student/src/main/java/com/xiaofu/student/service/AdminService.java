//管理后台：用户与登录设备管理
package com.xiaofu.student.service;

import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.dto.AdminSessionView;
import com.xiaofu.student.dto.AdminUserDetailResponse;
import com.xiaofu.student.dto.AdminUserListResponse;
import com.xiaofu.student.dto.AdminUserView;
import com.xiaofu.student.entity.RefreshToken;
import com.xiaofu.student.entity.User;
import com.xiaofu.student.entity.UserStatus;
import com.xiaofu.student.repository.RefreshTokenRepository;
import com.xiaofu.student.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理后台服务：列出所有用户、查看登录记录（设备）、禁用 / 启用账号、踢设备下线。
 *
 * <p>权限：所有方法开头都过一遍 {@link #requireAdmin}。项目没有开启方法级注解，
 * 权限就在服务里显式判断（与 TaskService.restore 的做法一致）。
 *
 * <p>禁用账号要同时做到三件事，缺一不可：
 * <ol>
 *   <li>{@code users.status = DISABLED} —— 挡住登录（AuthService.login）与续期（AuthService.refresh）；</li>
 *   <li>撤销该用户全部 refresh token —— JWT 过滤器按会话校验，旧 access token 立即失效；</li>
 *   <li>{@code CurrentUserProvider} 兜底 —— 连不带会话标识的老 token 也一并拦掉。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    /** 详情里最多回看多少条登录记录（登录历史可能很长）。 */
    private static final int MAX_SESSIONS = 100;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLogger auditLogger;

    // ==================== 查询 ====================

    /** 用户列表（分页 + 关键词 + 状态筛选），附带顶部统计。 */
    @Transactional(readOnly = true)
    public AdminUserListResponse list(User me, int page, int limit, String search, String status) {
        requireAdmin(me);
        int safePage = Math.max(1, page);
        int safeLimit = Math.min(Math.max(1, limit <= 0 ? DEFAULT_LIMIT : limit), MAX_LIMIT);
        UserStatus statusFilter = parseStatus(status);
        String like = StringUtils.hasText(search)
                ? "%" + search.trim().toLowerCase(Locale.ROOT) + "%"
                : null;

        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (like != null) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("email")), like)));
            }
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<User> result = userRepository.findAll(spec,
                PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.DESC, "id")));
        Instant now = Instant.now();
        Map<Long, List<RefreshToken>> sessions = sessionsOf(
                result.getContent().stream().map(User::getId).toList());

        List<AdminUserView> users = result.getContent().stream()
                .map(user -> toView(user, sessions.getOrDefault(user.getId(), List.of()), now))
                .toList();

        return new AdminUserListResponse(users, result.getTotalElements(), safePage, safeLimit,
                userRepository.count(),
                userRepository.countByStatus(UserStatus.ACTIVE),
                userRepository.countByStatus(UserStatus.DISABLED),
                refreshTokenRepository.countByRevokedAtIsNullAndExpiresAtAfter(now));
    }

    /** 用户详情：账号信息 + 登录记录（含已下线，最近活跃在前）。 */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse detail(User me, Long userId) {
        requireAdmin(me);
        User user = requireUser(userId);
        Instant now = Instant.now();
        List<RefreshToken> tokens = refreshTokenRepository.findByUserIdOrderByLastUsedAtDesc(userId);
        List<AdminSessionView> sessions = tokens.stream()
                .limit(MAX_SESSIONS)
                .map(token -> toSession(token, now))
                .toList();
        long revoked = tokens.stream().filter(token -> token.getRevokedAt() != null).count();
        return new AdminUserDetailResponse(toView(user, tokens, now), sessions, revoked);
    }

    // ==================== 禁用 / 启用 ====================

    /**
     * 禁用 / 启用账号。
     *
     * @return 给管理员看的提示文案
     */
    @Transactional
    public String setDisabled(User me, Long userId, boolean disabled, String ip) {
        requireAdmin(me);
        if (Objects.equals(me.getId(), userId)) {
            // 自己禁自己会直接把自己锁在外面，直接拒绝
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能禁用当前登录的管理员账号");
        }
        User target = requireUser(userId);

        if (disabled) {
            if (target.getStatus() == UserStatus.DISABLED) {
                return "该账号已经是禁用状态";
            }
            target.setStatus(UserStatus.DISABLED);
            target.setFailedLoginCount(0);
            target.setLockedUntil(null);
            userRepository.save(target);
            // 关键一步：撤销全部登录态，让已经登录的设备立刻掉线（不必等 access token 过期）
            Instant now = Instant.now();
            long online = refreshTokenRepository
                    .countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now);
            int revoked = refreshTokenRepository.revokeAllByUserId(userId, now);
            auditLogger.log("ADMIN_USER_DISABLED", userId, target.getUsername(), ip,
                    "REVOKED=" + revoked + " ONLINE=" + online);
            log.info("管理员 {} 禁用账号 {}（在线设备 {} 台，撤销登录态 {} 条）",
                    me.getId(), userId, online, revoked);
            return online > 0
                    ? "已禁用该账号，并踢下线 " + online + " 个登录设备"
                    : "已禁用该账号";
        }

        if (target.getStatus() != UserStatus.DISABLED) {
            return "该账号当前不是禁用状态";
        }
        // 邮箱没验证过的账号不能直接置为 ACTIVE，否则等于绕过了邮箱验证
        target.setStatus(target.isEmailVerified() ? UserStatus.ACTIVE : UserStatus.PENDING);
        target.setFailedLoginCount(0);
        target.setLockedUntil(null);
        userRepository.save(target);
        auditLogger.log("ADMIN_USER_ENABLED", userId, target.getUsername(), ip, "SUCCESS");
        log.info("管理员 {} 启用账号 {}", me.getId(), userId);
        return target.isEmailVerified() ? "已启用该账号，可以正常登录了" : "已解除禁用，但该邮箱仍未验证";
    }

    // ==================== 登录设备 ====================

    /** 踢掉某个用户的全部设备（不禁用账号，对方可以重新登录）。 */
    @Transactional
    public String revokeUserSessions(User me, Long userId, String ip) {
        requireAdmin(me);
        User target = requireUser(userId);
        Instant now = Instant.now();
        // 先数「真正在线」的，再撤销（撤销会把已过期的历史记录也一并标记，返回值会虚高）
        long online = refreshTokenRepository
                .countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now);
        int revoked = refreshTokenRepository.revokeAllByUserId(userId, now);
        auditLogger.log("ADMIN_SESSIONS_REVOKED", userId, target.getUsername(), ip,
                "REVOKED=" + revoked + " ONLINE=" + online);
        return online > 0 ? "已踢下线 " + online + " 个登录设备" : "该账号当前没有在线设备";
    }

    /** 踢掉单个登录设备。 */
    @Transactional
    public String revokeSession(User me, Long sessionId, String ip) {
        requireAdmin(me);
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "登录记录不存在");
        }
        RefreshToken token = refreshTokenRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "登录记录不存在"));
        if (token.getRevokedAt() != null) {
            return "该设备已经下线了";
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            return "该登录记录已过期";
        }
        token.setRevokedAt(Instant.now());
        refreshTokenRepository.save(token);
        auditLogger.log("ADMIN_SESSION_REVOKED", token.getUserId(), null, ip, "SESSION=" + sessionId);
        return "该设备已下线";
    }

    // ==================== 内部工具 ====================

    private void requireAdmin(User me) {
        if (me == null || !me.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    /** 非法状态值按「不筛选」处理，不让手改 URL 把请求打成 500。 */
    private UserStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return UserStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 一次取完这一页用户的登录记录，避免逐用户查询。 */
    private Map<Long, List<RefreshToken>> sessionsOf(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return refreshTokenRepository.findByUserIdInOrderByLastUsedAtDesc(userIds).stream()
                .collect(Collectors.groupingBy(RefreshToken::getUserId,
                        HashMap::new, Collectors.toList()));
    }

    private AdminUserView toView(User user, List<RefreshToken> sessions, Instant now) {
        long active = sessions.stream().filter(token -> isActive(token, now)).count();
        // 查询已按 lastUsedAt 倒序，第一条即最近一次登录
        RefreshToken latest = sessions.isEmpty() ? null : sessions.get(0);
        return new AdminUserView(user.getId(), user.getUsername(), user.getEmail(), user.getAvatarUrl(),
                user.isEmailVerified(), user.getStatus().name(), user.isAdmin(),
                toLocal(user.getCreatedAt()),
                latest == null ? null : toLocal(latest.getLastUsedAt()),
                latest == null ? null : latest.getIp(),
                active, sessions.size(),
                user.getLockedUntil() != null && user.getLockedUntil().isAfter(now));
    }

    private AdminSessionView toSession(RefreshToken token, Instant now) {
        return new AdminSessionView(token.getId(), token.getUserId(), token.getDeviceName(),
                token.getIp(), token.getUserAgent(),
                toLocal(token.getCreatedAt()), toLocal(token.getLastUsedAt()),
                toLocal(token.getExpiresAt()), toLocal(token.getRevokedAt()),
                isActive(token, now));
    }

    private boolean isActive(RefreshToken token, Instant now) {
        return token.getRevokedAt() == null
                && token.getExpiresAt() != null
                && token.getExpiresAt().isAfter(now);
    }

    /** Instant → 本地时间（JVM 时区已由 TimeZoneConfig 锁到 Asia/Shanghai）。 */
    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
