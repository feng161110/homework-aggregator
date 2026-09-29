//email_verification_codes 表：邮箱验证码
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
@Table(name = "email_verification_codes",
        indexes = @Index(name = "idx_evc_user_id", columnList = "user_id"))
@Getter
@Setter
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 验证码只存哈希值，不存明文（6 位数字，配合尝试次数限制防爆破）。 */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 已尝试校验次数，超过上限即作废，防止暴力猜测。 */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
