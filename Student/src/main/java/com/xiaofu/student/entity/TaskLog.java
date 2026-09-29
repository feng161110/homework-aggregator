//任务执行日志
package com.xiaofu.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 任务执行日志：每执行一次写一行，自动与手动共用，靠 triggerType 区分。 */
@Entity
@Table(name = "task_logs")
@Getter
@Setter
public class TaskLog {

    /** 自动调度触发。 */
    public static final String TRIGGER_AUTO = "auto";
    /** 用户手动「立即发送」。 */
    public static final String TRIGGER_MANUAL = "manual";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    /** 1 成功 / 0 失败。 */
    @Column(name = "success", nullable = false)
    private Integer success = 0;

    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType = TRIGGER_AUTO;

    @Column(name = "message", nullable = false, length = 255)
    private String message = "";
}
