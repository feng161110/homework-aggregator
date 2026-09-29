//表
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
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, unique = true, length = 128)
    private String email;

    @Column(nullable = false)
    private boolean emailVerified = false;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** 头像地址（相对路径，如 /uploads/avatars/xxx.png），未上传时为 null。 */
    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    /**
     * 账号状态。
     *
     * <p><b>必须显式声明 columnDefinition</b>：Hibernate 6 在 MySQL 上会把
     * {@code @Enumerated(STRING)} 建成原生 {@code enum('PENDING','ACTIVE',...)}，
     * 而 {@code ddl-auto=update} 不会给已存在的列追加枚举标签 ——
     * 结果是以后每新增一个状态值（比如 DISABLED），写入时都会
     * "Data truncated for column 'status'" 直接 500。
     * 固定成 varchar 就再也不会踩这个坑。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private UserStatus status = UserStatus.PENDING;

    /**
     * 管理员：可查看全站提醒任务、恢复已删除任务。
     * 注册时一律为 false，需要管理员时在库里手动置 1：
     * {@code UPDATE users SET is_admin = 1 WHERE username = 'xxx';}
     */
    @Column(name = "is_admin", nullable = false)
    private boolean isAdmin = false;

    /**
     * 失败计数与锁定时间由登录逻辑同步写入（用于审计与排查），
     * 实时锁定判断由 LoginAttemptService 内存计数负责，避免账号枚举差异。
     */
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

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