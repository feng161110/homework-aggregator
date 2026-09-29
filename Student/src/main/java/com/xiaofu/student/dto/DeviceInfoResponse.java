//登录设备信息响应
package com.xiaofu.student.dto;

import java.time.Instant;

/**
 * 登录设备信息。
 *
 * @param id        设备会话记录 ID
 * @param deviceName 设备名称（如「Chrome · Windows」）
 * @param userAgent 原始 User-Agent
 * @param ip        登录来源 IP
 * @param current   是否为当前发起请求的设备
 * @param loginAt   登录时间
 * @param lastUsedAt 最近活跃时间
 * @param expiresAt 登录态过期时间
 */
public record DeviceInfoResponse(Long id, String deviceName, String userAgent, String ip,
                                 boolean current, Instant loginAt, Instant lastUsedAt, Instant expiresAt) {
}
