//POST /api/task 的请求体
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * POST /api/task 的请求体：所有写操作共用一个 DTO，靠 action 区分（对应原型 tasks.php 的 action 分发）。
 *
 * @param action        create | execute | cancel | enable | update | delete | restore
 * @param taskData      create 用
 * @param scheduledTime create / update 用，形如 {@code 2026-10-08 08:00:00}（也兼容 ISO 带 T）
 * @param taskType      create 用，缺省 email_notification
 * @param taskId        execute / cancel / enable / update / delete / restore 用
 * @param repeat        update 用
 * @param repeatData    update 用
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskActionRequest(String action, TaskData taskData, String scheduledTime, String taskType,
                                Long taskId, String repeat, RepeatData repeatData) {
}
