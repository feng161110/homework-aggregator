//执行日志视图
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 一条执行日志。
 *
 * @param success     是否发送成功
 * @param triggerType auto（到点自动发）/ manual（用户点「立即发送」）
 */
public record TaskLogView(Long id,
                          @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime executedAt,
                          boolean success, String triggerType, String message) {
}
