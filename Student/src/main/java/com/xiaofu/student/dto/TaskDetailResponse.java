//任务详情响应
package com.xiaofu.student.dto;

import java.util.List;

/**
 * 任务详情 + 执行日志。
 *
 * @param logs 最近 50 条，最新的在前
 */
public record TaskDetailResponse(TaskView task, List<TaskLogView> logs) {
}
