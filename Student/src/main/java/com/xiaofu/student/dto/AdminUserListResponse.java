//管理后台：用户列表响应
package com.xiaofu.student.dto;

import java.util.List;

/**
 * 管理后台用户列表：分页数据 + 顶部统计（一次请求就能把页面填满）。
 *
 * @param totalUsers     全站账号数
 * @param activeUsers    状态为 ACTIVE 的账号数
 * @param disabledUsers  被禁用的账号数
 * @param onlineSessions 全站在线设备数
 */
public record AdminUserListResponse(List<AdminUserView> users, long total, int page, int limit,
                                    long totalUsers, long activeUsers, long disabledUsers,
                                    long onlineSessions) {
}
