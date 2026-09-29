//定时任务（作业提醒 / 自定义提醒）
package com.xiaofu.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 定时任务表 scheduled_tasks。
 *
 * <p><b>status 语义以代码为准，不要按建表注释理解</b>：
 * <ul>
 *   <li>0 待发送 —— 可执行（立即发送 / 再次发送 / 禁用）</li>
 *   <li>1 已发送 —— 仍可再发（一次性任务成功后落这个状态）</li>
 *   <li>2 已禁用 —— 不可执行，只能启用</li>
 *   <li>3 发送失败 —— 可再发</li>
 * </ul>
 *
 * <p>重复任务（daily / weekly / monthly）发送成功后<b>不置为已发送</b>，
 * 而是保持 status=0 并把 scheduled_time 前移；发送失败同样保持 0，不中断后续计划。
 *
 * <p>{@code taskData} 存 JSON 字符串（MySQL json 列），结构见 dto/TaskData。
 */
@Entity
@Table(name = "scheduled_tasks",
        indexes = {
                // 调度器每 30 秒执行的那条扫描：
                //   where status = 0 and is_deleted = 0 and scheduled_time <= now order by scheduled_time
                // 没有这个复合索引，任务一多就是每 30 秒一次全表扫描（还会排序）。
                @Index(name = "idx_task_due", columnList = "status,is_deleted,scheduled_time"),
                // 列表页按计划时间排序分页
                @Index(name = "idx_task_user_time", columnList = "user_id,is_deleted,scheduled_time")
        })
@Getter
@Setter
public class ScheduledTask {

    /** 待发送。 */
    public static final int STATUS_PENDING = 0;
    /** 已发送。 */
    public static final int STATUS_SENT = 1;
    /** 已禁用。 */
    public static final int STATUS_DISABLED = 2;
    /** 发送失败。 */
    public static final int STATUS_FAILED = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 固定 email_notification；作业 / 自定义靠 task_data.category 区分。 */
    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType = "email_notification";

    /** 任务信息 JSON。 */
    @Column(name = "task_data", nullable = false, columnDefinition = "json")
    private String taskData;

    /** 计划执行时间（Asia/Shanghai）。 */
    @Column(name = "scheduled_time", nullable = false)
    private LocalDateTime scheduledTime;

    @Column(name = "status", nullable = false)
    private Integer status = STATUS_PENDING;

    /** 最近一次实际执行时间（成功或失败都会写）。 */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 软删除：普通用户看不到，管理员可用 includeDeleted 查看并恢复。 */
    @Column(name = "is_deleted", nullable = false)
    private Integer isDeleted = 0;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
