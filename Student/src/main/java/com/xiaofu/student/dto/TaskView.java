//任务视图
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 列表 / 详情里的任务视图。
 *
 * @param status        0 待发送 / 1 已发送 / 2 已禁用 / 3 发送失败
 * @param scheduledTime 计划执行时间
 * @param executedAt    最近一次实际执行时间
 * @param isDeleted     1 表示已被软删除
 * @param taskData      已解析成对象的任务内容（前端不用再 JSON.parse）
 * @param ownerUsername 任务归属用户名，管理员用 allUsers 看全站时才填
 */
public record TaskView(Long id, Long userId, String taskType, Integer status,
                       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime scheduledTime,
                       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime executedAt,
                       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
                       Integer isDeleted, TaskData taskData, String ownerUsername) {
}
