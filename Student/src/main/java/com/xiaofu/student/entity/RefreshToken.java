//RefreshToken表
package com.xiaofu.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_rt_user_id", columnList = "user_id"),
                @Index(name = "idx_rt_session_id", columnList = "session_id")
        })
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** 本次登录会话标识，随 access token 的 sid 声明一同下发，用于识别「当前设备」。 */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /** 设备名称（由 User-Agent 解析），如「Chrome · Windows」。 */
    @Column(name = "device_name", length = 128)
    private String deviceName;

    /** 原始 User-Agent，便于排查与展示。 */
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** 登录来源 IP。 */
    @Column(name = "ip", length = 64)
    private String ip;

    /** 最近一次活跃时间（登录或刷新令牌时更新）。 */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}