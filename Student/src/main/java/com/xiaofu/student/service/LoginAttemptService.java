//登录失败计数与锁定（账号+IP，防枚举）
package com.xiaofu.student.service;

import com.xiaofu.student.config.AppProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    /** 失败记录保留时长：超过就清掉，避免被大量不存在的账号名撑爆内存 */
    private static final Duration ATTEMPT_TTL = Duration.ofHours(2);
    /** 记录数硬上限：先按 TTL 清理，仍超标就整体清空 */
    private static final int MAX_ENTRIES = 5000;

    private final int maxFailures;
    private final Duration lockDuration;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(AppProperties appProperties) {
        this.maxFailures = appProperties.getSecurity().getLoginMaxFailures();
        this.lockDuration = Duration.ofMinutes(appProperties.getSecurity().getLoginLockMinutes());
    }

    public boolean isLocked(String account, String ip) {
        Attempt attempt = attempts.get(key(account, ip));
        return attempt != null && attempt.lockedUntil != null && attempt.lockedUntil.isAfter(Instant.now());
    }

    public void recordFailure(String account, String ip) {
        // key 里含攻击者可任意构造的账号名，不清理会让这个 Map 无界增长（内存 DoS）
        if (attempts.size() >= MAX_ENTRIES) {
            prune();
        }
        Instant now = Instant.now();
        attempts.compute(key(account, ip), (k, existing) -> {
            Attempt attempt = existing != null ? existing : new Attempt();
            if (attempt.lockedUntil != null && attempt.lockedUntil.isBefore(now)) {
                attempt.failures = 0;
                attempt.lockedUntil = null;
            }
            attempt.failures++;
            attempt.lastFailureAt = now;
            if (attempt.failures >= maxFailures) {
                attempt.lockedUntil = now.plus(lockDuration);
            }
            return attempt;
        });
    }

    public void clear(String account, String ip) {
        attempts.remove(key(account, ip));
    }

    /** 清掉超过 TTL 的记录；极端情况下仍超标就整体清空（宁可放宽限制，也不让内存无界增长） */
    private void prune() {
        Instant deadline = Instant.now().minus(ATTEMPT_TTL);
        attempts.entrySet().removeIf(entry -> {
            Instant at = entry.getValue().lastFailureAt;
            return at != null && at.isBefore(deadline);
        });
        if (attempts.size() >= MAX_ENTRIES) {
            attempts.clear();
        }
    }

    private String key(String account, String ip) {
        return account.toLowerCase(Locale.ROOT) + "|" + ip;
    }

    private static final class Attempt {
        private int failures;
        private Instant lockedUntil;
        private Instant lastFailureAt;
    }
}