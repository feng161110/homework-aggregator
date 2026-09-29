//任务列表响应
package com.xiaofu.student.dto;

import java.util.List;

/**
 * 任务列表。
 *
 * @param total   符合条件的总条数（分页用）
 * @param isAdmin 当前用户是否管理员（前端据此决定是否显示「查看所有人 / 查看已删除」）
 */
public record TaskListResponse(List<TaskView> tasks, long total, int page, int limit, boolean isAdmin) {
}
