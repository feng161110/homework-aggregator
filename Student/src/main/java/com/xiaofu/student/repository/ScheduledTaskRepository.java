package com.xiaofu.student.repository;

import com.xiaofu.student.entity.ScheduledTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRepository
        extends JpaRepository<ScheduledTask, Long>, JpaSpecificationExecutor<ScheduledTask> {

    /**
     * 扫描到期任务（自动调度用）：待发送、未删除、已到点，最早到期的优先。
     * 用 Pageable 限制每轮条数，避免堆积时一次性全捞出来。
     */
    @Query("""
            select t from ScheduledTask t
            where t.status = 0 and t.isDeleted = 0 and t.scheduledTime <= :now
            order by t.scheduledTime asc
            """)
    List<ScheduledTask> findDueTasks(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 取任务并加行锁（SELECT ... FOR UPDATE）。
     * 执行邮件发送前必须走这个，防止自动调度与手动「立即发送」同时发两封。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ScheduledTask t where t.id = :id")
    Optional<ScheduledTask> findByIdForUpdate(@Param("id") Long id);
}
