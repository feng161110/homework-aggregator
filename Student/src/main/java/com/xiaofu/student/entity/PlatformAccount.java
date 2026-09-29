//表：平台登录凭证
package com.xiaofu.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户在某第三方平台上的登录凭证（账号 + 加密密码 + Cookie）。
 * 一个用户 + 一个平台只保留一条记录（uk_user_platform）。
 * <p>
 * 只有服务器内部会读取 cookiesJson / passwordCipher / extraJson，
 * 对外响应一律不回传密码明文。
 */
@Entity
@Table(name = "platform_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_platform", columnNames = {"user_id", "platform"}))
@Getter
@Setter
public class PlatformAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 平台标识：zhihuishu / yuketang / xuexitong。 */
    @Column(nullable = false, length = 32)
    private String platform;

    /** 平台账号（手机号）。 */
    @Column(length = 128)
    private String account;

    /** AES-256-GCM 加密后的平台密码（Base64），未保存密码时为 null。 */
    @Column(name = "password_cipher", length = 512)
    private String passwordCipher;

    /** 完整 Cookie 字典的 JSON。 */
    @Column(name = "cookies_json", columnDefinition = "TEXT")
    private String cookiesJson;

    /** 平台附加参数（如智慧树的 validate / fp）的 JSON。 */
    @Column(name = "extra_json", columnDefinition = "TEXT")
    private String extraJson;

    /**
     * Cookie 状态。同 User.status：显式 varchar，避免 Hibernate 建成原生 enum 后
     * 以后新增枚举值写不进去（update 不会给已有列追加枚举标签）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cookie_status", nullable = false, length = 16, columnDefinition = "varchar(16)")
    private PlatformCookieStatus cookieStatus = PlatformCookieStatus.UNKNOWN;

    /** 凭证来源：password（账号密码自动登录）/ plugin（浏览器助手）/ manual（手动粘贴）。 */
    @Column(name = "cookie_source", length = 16)
    private String cookieSource;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
