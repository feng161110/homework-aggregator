//到期任务自动执行
package com.xiaofu.student.scheduler;

import com.xiaofu.student.config.AppProperties;
import com.xiaofu.student.entity.ScheduledTask;
import com.xiaofu.student.repository.ScheduledTaskRepository;
import com.xiaofu.student.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动调度器：每 30 秒把到点的待发送任务捞出来发掉（原项目缺这一环，只能手动执行）。
 *
 * <p><b>类名不能叫 TaskScheduler</b>：Spring Boot 的 TaskSchedulingAutoConfiguration
 * 已经注册了一个名为 {@code taskScheduler} 的 Bean（@Scheduled 就靠它跑），
 * 同名会抛 BeanDefinitionOverrideException 直接启动失败。
 *
 * <p>单实例部署即可。多实例部署会同时捞到同一批任务，虽然 {@code SELECT ... FOR UPDATE}
 * 行锁能保证不会重复发信，但会白跑一次 SMTP —— 那时应接入 ShedLock / Quartz。
 */
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ScheduledTaskRepository taskRepository;
    private final TaskService taskService;
    private final AppProperties appProperties;

    @Scheduled(fixedDelayString = "${app.remind.scan-delay-ms:30000}", initialDelayString = "10000")
    public void scanAndExecute() {
        List<ScheduledTask> due;
        try {
            due = taskRepository.findDueTasks(LocalDateTime.now(),
                    PageRequest.of(0, appProperties.getRemind().getScanBatchSize()));
        } catch (Exception e) {
            // 扫描本身失败（比如数据库抖动）不能把调度线程打死，下一轮会重试
            log.error("扫描到期任务失败：{}", e.getMessage(), e);
            return;
        }
        if (due.isEmpty()) {
            return;
        }
        log.info("扫描到 {} 条到期任务", due.size());
        for (ScheduledTask task : due) {
            try {
                // 自动触发：以任务归属人身份执行，权限校验自然通过
                taskService.execute(task.getId(), task.getUserId(), false, true);
            } catch (Exception e) {
                // 单条失败不影响这一批；失败原因已由 execute 写进 task_logs
                log.warn("自动执行任务 {} 失败：{}", task.getId(), e.getMessage());
            }
        }
    }
}
