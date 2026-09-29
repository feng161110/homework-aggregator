//用户信息响应
package com.xiaofu.student.dto;

import java.time.Instant;

/**
 * 当前登录用户信息。
 *
 * @param status   PENDING / ACTIVE / LOCKED / DISABLED
 * @param isAdmin  管理员标记：前端据此显示「管理后台」入口
 */
public record UserInfoResponse(Long id, String username, String email, String avatarUrl,
                               boolean emailVerified, String status, boolean isAdmin, Instant createdAt) {
}
