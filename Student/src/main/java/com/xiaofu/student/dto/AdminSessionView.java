//管理后台：单个登录设备 / 登录记录
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 一条登录记录（refresh_tokens 的一行，即一台设备的一次登录）。
 *
 * @param active    未撤销且未过期 = 现在还在线
 * @param revokedAt 被下线（用户自己退出 / 被管理员踢 / 改密码）的时间
 */
public record AdminSessionView(Long id, Long userId, String deviceName, String ip, String userAgent,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastUsedAt,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime expiresAt,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime revokedAt,
                               boolean active) {
}
