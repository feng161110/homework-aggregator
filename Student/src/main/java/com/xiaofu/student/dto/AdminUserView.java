//管理后台：用户行
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 管理后台的用户列表行。
 *
 * <p>时间统一用 {@link LocalDateTime} + 固定格式：与提醒页的 TaskView 一致，
 * 前端可以直接显示。注意别用 Instant + @JsonFormat —— 那对 Instant 不生效，会输出带 Z 的 ISO 串。
 *
 * @param status         PENDING / ACTIVE / LOCKED / DISABLED
 * @param isAdmin        管理员标记
 * @param lastActiveAt   最近一次登录或刷新令牌的时间（没有登录记录时为 null）
 * @param lastLoginIp    最近一次登录来源 IP
 * @param activeSessions 当前在线设备数（未撤销且未过期）
 * @param totalSessions  累计登录记录数（含已下线）
 * @param locked         是否正处于「密码错误过多」的临时锁定中
 */
public record AdminUserView(Long id, String username, String email, String avatarUrl,
                            boolean emailVerified, String status, boolean isAdmin,
                            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
                            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastActiveAt,
                            String lastLoginIp, long activeSessions, long totalSessions, boolean locked) {
}
