//任务概览统计
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 提醒页顶部的概览：各状态数量 + 下一封的发送时间。
 *
 * @param total             未删除任务总数（不含已软删）
 * @param nextScheduledTime 待发送任务里最早的那个时间点，没有则为 null
 */
public record TaskStatsResponse(long pending, long sent, long disabled, long failed, long total,
                                @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime nextScheduledTime) {
}
