//管理后台：用户详情响应
package com.xiaofu.student.dto;

import java.util.List;

/**
 * 用户详情：账号信息 + 全部登录记录（登录历史，最近活跃在前）。
 *
 * @param revokedSessions 已下线的记录数（列表只回看最近若干条，这个数是真实总数）
 */
public record AdminUserDetailResponse(AdminUserView user, List<AdminSessionView> sessions,
                                      long revokedSessions) {
}
