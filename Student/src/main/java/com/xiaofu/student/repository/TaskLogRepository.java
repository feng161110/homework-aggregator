package com.xiaofu.student.repository;

import com.xiaofu.student.entity.TaskLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskLogRepository extends JpaRepository<TaskLog, Long> {

    /** 某个任务的执行日志，最近的在最前面（详情弹窗用，最多 50 条）。 */
    List<TaskLog> findTop50ByTaskIdOrderByIdDesc(Long taskId);
}
