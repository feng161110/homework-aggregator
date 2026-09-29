//作业提醒 / 自定义提醒任务服务
package com.xiaofu.student.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.config.AppProperties;
import com.xiaofu.student.dto.RepeatData;
import com.xiaofu.student.dto.TaskActionRequest;
import com.xiaofu.student.dto.TaskData;
import com.xiaofu.student.dto.TaskDetailResponse;
import com.xiaofu.student.dto.TaskListResponse;
import com.xiaofu.student.dto.TaskLogView;
import com.xiaofu.student.dto.TaskStatsResponse;
import com.xiaofu.student.dto.TaskView;
import com.xiaofu.student.entity.ScheduledTask;
import com.xiaofu.student.entity.TaskLog;
import com.xiaofu.student.entity.User;
import com.xiaofu.student.entity.UserStatus;
import com.xiaofu.student.repository.ScheduledTaskRepository;
import com.xiaofu.student.repository.TaskLogRepository;
import com.xiaofu.student.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 定时任务服务（对应原项目 tasks.php）。
 *
 * <p>状态机、时间归一化、软删、日志等规则与原项目保持一致，要点：
 * <ul>
 *   <li>一次性任务（none）拒绝过去时间；重复任务会先推进到下一个未来执行点</li>
 *   <li>重复任务发送成功后不置「已发送」，而是保持待发送并前移 scheduled_time；失败也保持待发送</li>
 *   <li>收件人一律以当前登录账号的邮箱为准，不采信前端传值</li>
 *   <li>普通用户拿别人的任务一律按「不存在」处理（不泄露任务是否存在）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ScheduledTaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final UserRepository userRepository;
    private final RepeatRuleService repeatRuleService;
    private final MailService mailService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    // ==================== 创建 ====================

    /**
     * 创建任务，返回任务 id。
     *
     * <p>时间归一化：一次性任务必须是未来；重复任务若落在过去，推进到下一个未来执行点；
     * immediate（立即发送）允许过去时间，交给调度器下一轮捞走。
     */
    @Transactional
    public Long create(User me, TaskActionRequest req) {
        if (req.taskData() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件信息不完整");
        }
        TaskData data = req.taskData().normalized();
        String repeat = data.repeatOrNone();
        LocalDateTime time = parseTime(req.scheduledTime(), true);
        if (repeatRuleService.isRepeat(repeat)) {
            validateRepeat(repeat, data.repeatData());
            time = repeatRuleService.computeNext(time, repeat, data.repeatData());
        } else if (!RepeatRuleService.REPEAT_IMMEDIATE.equals(repeat) && !time.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发送时间已过，请选择未来的时间");
        }
        validateMail(data);

        // 收件人锁定为当前账号邮箱：否则这个接口就成了任意收件人的发信中转
        ScheduledTask task = new ScheduledTask();
        task.setUserId(me.getId());
        task.setTaskType(StringUtils.hasText(req.taskType()) ? req.taskType() : "email_notification");
        task.setTaskData(writeJson(data.withTo(me.getEmail())));
        task.setScheduledTime(time);
        task.setStatus(ScheduledTask.STATUS_PENDING);
        task.setIsDeleted(0);
        return taskRepository.save(task).getId();
    }

    // ==================== 查询 ====================

    /**
     * 任务列表（分页 + 筛选）。
     *
     * <p>可见范围：普通用户只有自己的未删除任务；管理员默认同样只看自己的，
     * 显式传 allUsers 才能看全站，includeDeleted 决定看正常的还是已删除的。
     */
    @Transactional(readOnly = true)
    public TaskListResponse list(User me, int page, int limit, String search, Integer status,
                                boolean allUsers, boolean includeDeleted) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.min(Math.max(1, limit <= 0 ? DEFAULT_LIMIT : limit), MAX_LIMIT);
        Specification<ScheduledTask> spec = buildSpec(me, search, status, allUsers, includeDeleted);

        // 按时间正序：第一页就是「接下来要发的」，与页面顶部的「接下来」一致。
        // 倒序的话最近要发的会被挤到最后一页，只在单页里调整显示顺序是救不回来的。
        Page<ScheduledTask> result = taskRepository.findAll(spec,
                PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.ASC, "scheduledTime")));
        Map<Long, String> ownerNames = ownerNames(result.getContent());
        boolean crossUser = me.isAdmin() && allUsers;
        List<TaskView> tasks = result.getContent().stream()
                .map(task -> toView(task, crossUser ? ownerNames.get(task.getUserId()) : null))
                .toList();
        return new TaskListResponse(tasks, result.getTotalElements(), safePage, safeLimit, me.isAdmin());
    }

    /** 概览：各状态数量 + 下一封的发送时间（可见范围与列表一致，方便页面顶部显示「接下来」）。 */
    @Transactional(readOnly = true)
    public TaskStatsResponse stats(User me, boolean allUsers) {
        long pending = countByStatus(me, allUsers, ScheduledTask.STATUS_PENDING);
        long sent = countByStatus(me, allUsers, ScheduledTask.STATUS_SENT);
        long disabled = countByStatus(me, allUsers, ScheduledTask.STATUS_DISABLED);
        long failed = countByStatus(me, allUsers, ScheduledTask.STATUS_FAILED);

        Specification<ScheduledTask> nextSpec = buildSpec(me, null, ScheduledTask.STATUS_PENDING, allUsers, false);
        LocalDateTime next = taskRepository
                .findAll(nextSpec, PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "scheduledTime")))
                .stream()
                .findFirst()
                .map(ScheduledTask::getScheduledTime)
                .orElse(null);

        return new TaskStatsResponse(pending, sent, disabled, failed, pending + sent + disabled + failed, next);
    }

    private long countByStatus(User me, boolean allUsers, int status) {
        return taskRepository.count(buildSpec(me, null, status, allUsers, false));
    }

    /** 任务详情：任务本体 + 最近 50 条执行日志。 */
    @Transactional(readOnly = true)
    public TaskDetailResponse detail(User me, Long taskId) {
        ScheduledTask task = requireVisible(me, taskId);
        List<TaskLogView> logs = taskLogRepository.findTop50ByTaskIdOrderByIdDesc(taskId).stream()
                .map(l -> new TaskLogView(l.getId(), l.getExecutedAt(), l.getSuccess() != null && l.getSuccess() == 1,
                        l.getTriggerType(), l.getMessage()))
                .toList();
        String ownerName = me.isAdmin() ? ownerNames(List.of(task)).get(task.getUserId()) : null;
        return new TaskDetailResponse(toView(task, ownerName), logs);
    }

    // ==================== 执行 ====================

    /**
     * 执行任务：手动「立即发送」与自动调度共用这一个方法，只是 triggerType 不同。
     *
     * <p>{@code noRollbackFor}：发送失败时也要把 status 与失败日志落库，然后再抛 500 给前端；
     * 默认的回滚会把刚写进去的日志一起抹掉，前端就永远看不到失败原因。
     *
     * @param operatorUserId 操作人（手动=当前登录用户；自动=任务归属人）
     * @param auto           true 记 auto 日志，false 记 manual
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void execute(Long taskId, Long operatorUserId, boolean operatorIsAdmin, boolean auto) {
        if (taskId == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        // 行锁：防止自动调度与手动发送同时发两封
        ScheduledTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        if (!operatorIsAdmin && !Objects.equals(task.getUserId(), operatorUserId)) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        if (task.getIsDeleted() != null && task.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.TASK_DISABLED);
        }
        if (task.getStatus() != null && task.getStatus() == ScheduledTask.STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.TASK_DISABLED);
        }
        /*
         * 自动触发必须重新确认「还是待发送、而且确实到点了」。
         * 调度器是先扫描出一批 id 再逐个执行，这中间（批量大时可能几十秒）用户完全可能
         * 手动「立即发送」过 —— 那个任务已经是已发送 / 时间已前移，照着旧名单再发一次
         * 就是重复发信。手动触发不走这个判断（就是要重发）。
         */
        if (auto && (task.getStatus() == null || task.getStatus() != ScheduledTask.STATUS_PENDING
                || task.getScheduledTime() == null
                || task.getScheduledTime().isAfter(LocalDateTime.now()))) {
            log.debug("自动执行跳过任务 {}：状态 {}，计划时间 {}",
                    taskId, task.getStatus(), task.getScheduledTime());
            return;
        }

        TaskData data = readJson(task.getTaskData());
        String repeat = data.repeatOrNone();
        User owner = userRepository.findById(task.getUserId()).orElse(null);

        /*
         * 账号被管理员禁用：名下的提醒一并停发（「禁用账号」的自然含义，也是「其它逻辑」的一部分）。
         *
         * 自动触发：重复任务把计划前移，免得调度器每 30 秒拿同一把过期时间反复捞；
         *           一次性任务保持不动 —— 账号一旦启用、到点就能正常发出去，
         *           所以这条路径只记 debug，否则会在日志里每 30 秒刷一行。
         * 手动触发：必须如实报错。否则管理员点了「立即发送」，控制器照旧回「已发送」，
         *           信其实没发出去 —— 这种「假成功」比明确失败糟得多。
         */
        if (owner != null && owner.getStatus() == UserStatus.DISABLED) {
            if (!auto) {
                throw new BusinessException(ErrorCode.TASK_DISABLED, "任务所属账号已被禁用，无法发送");
            }
            if (repeatRuleService.isRepeat(repeat)) {
                log.info("任务 {} 跳过发送：账号 {} 已被禁用，计划已前移", taskId, task.getUserId());
                task.setScheduledTime(repeatRuleService.computeNext(
                        task.getScheduledTime(), repeat, data.repeatData()));
                taskRepository.save(task);
            } else {
                log.debug("任务 {} 跳过发送：账号 {} 已被禁用", taskId, task.getUserId());
            }
            return;
        }

        // 收件人以库里账号邮箱为准；账号被删这种极端情况才回退到任务里存的值
        String to = owner != null ? owner.getEmail() : data.to();
        // 没有占位符系统：主题与正文就是最终内容，原样发出
        boolean sent = mailService.sendCustom(to, data.subject(), data.content(), data.isHtml());
        LocalDateTime now = LocalDateTime.now();
        task.setExecutedAt(now);
        String trigger = auto ? TaskLog.TRIGGER_AUTO : TaskLog.TRIGGER_MANUAL;

        if (sent) {
            if (repeatRuleService.isRepeat(repeat)) {
                // 重复任务：继续排在队列里，时间前移到下一个执行点
                task.setStatus(ScheduledTask.STATUS_PENDING);
                task.setScheduledTime(repeatRuleService.computeNext(task.getScheduledTime(), repeat, data.repeatData()));
            } else {
                task.setStatus(ScheduledTask.STATUS_SENT);
            }
            taskRepository.save(task);
            writeLog(task, true, trigger, "发送成功", now);
            return;
        }

        /*
         * 失败：一次性任务标记失败（不再被扫描）；重复任务保持待发送，但要同时把时间前移 ——
         * 否则 scheduled_time 一直停在过去，扫描器每 30 秒就会拿它再撞一次 SMTP：
         * 日志被灌满、QQ 邮箱很快会因为这个频率把账号限掉。
         * 这一次算「过去了」，下一次仍按原规则照常发。
         */
        boolean repeating = repeatRuleService.isRepeat(repeat);
        task.setStatus(repeating ? ScheduledTask.STATUS_PENDING : ScheduledTask.STATUS_FAILED);
        if (repeating) {
            task.setScheduledTime(repeatRuleService.computeNext(
                    task.getScheduledTime(), repeat, data.repeatData()));
        }
        taskRepository.save(task);
        writeLog(task, false, trigger, "邮件发送失败", now);
        throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED, "邮件发送失败，请稍后重试");
    }

    // ==================== 修改规则 / 禁用 / 启用 / 删除 / 恢复 ====================

    /**
     * 修改发送规则：只动时间和重复规则，邮件内容不变。
     * 原本「已发送 / 发送失败」的一次性任务改到未来后，重新变成待发送。
     */
    @Transactional
    public void update(User me, TaskActionRequest req) {
        ScheduledTask task = requireVisible(me, req.taskId());
        TaskData data = readJson(task.getTaskData());
        String repeat = StringUtils.hasText(req.repeat()) ? req.repeat().toLowerCase() : data.repeatOrNone();
        RepeatData repeatData = req.repeatData() != null ? req.repeatData() : data.repeatData();

        LocalDateTime time = parseTime(req.scheduledTime(), true);
        if (repeatRuleService.isRepeat(repeat)) {
            validateRepeat(repeat, repeatData);
            time = repeatRuleService.computeNext(time, repeat, repeatData);
        } else if (!time.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发送时间已过，请选择未来的时间");
        }

        TaskData updated = new TaskData(data.to(), data.subject(), data.content(), data.format(), repeat,
                repeatData, data.category());
        task.setTaskData(writeJson(updated));
        task.setScheduledTime(time);
        if (!repeatRuleService.isRepeat(repeat)
                && (Integer.valueOf(ScheduledTask.STATUS_SENT).equals(task.getStatus())
                || Integer.valueOf(ScheduledTask.STATUS_FAILED).equals(task.getStatus()))) {
            task.setStatus(ScheduledTask.STATUS_PENDING);
        }
        taskRepository.save(task);
    }

    /** 禁用：待发送 / 已发送 / 发送失败都能禁；已禁用的再禁就报「任务不存在或已禁用」。 */
    @Transactional
    public void cancel(User me, Long taskId) {
        ScheduledTask task = requireVisible(me, taskId);
        if (task.getStatus() != null && task.getStatus() == ScheduledTask.STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.TASK_DISABLED);
        }
        task.setStatus(ScheduledTask.STATUS_DISABLED);
        taskRepository.save(task);
    }

    /** 启用：只对已禁用状态生效。 */
    @Transactional
    public void enable(User me, Long taskId) {
        ScheduledTask task = requireVisible(me, taskId);
        if (task.getStatus() == null || task.getStatus() != ScheduledTask.STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.TASK_DISABLED);
        }
        task.setStatus(ScheduledTask.STATUS_PENDING);
        advanceIfStale(task);
        taskRepository.save(task);
    }

    /**
     * 重复任务停用期间过了点，再启用时不该立刻补发一封（用户只是重新打开开关而已）：
     * 把时间推进到下一个未来执行点。一次性任务没有「下一次」，保持原样（到点就发）。
     */
    private void advanceIfStale(ScheduledTask task) {
        TaskData data = readJson(task.getTaskData());
        String repeat = data.repeatOrNone();
        if (!repeatRuleService.isRepeat(repeat) || task.getScheduledTime() == null
                || task.getScheduledTime().isAfter(LocalDateTime.now())) {
            return;
        }
        task.setScheduledTime(repeatRuleService.computeNext(
                task.getScheduledTime(), repeat, data.repeatData()));
    }

    /** 软删除：普通用户只能删自己的，管理员不限。 */
    @Transactional
    public void delete(User me, Long taskId) {
        ScheduledTask task = requireVisible(me, taskId);
        task.setIsDeleted(1);
        taskRepository.save(task);
    }

    /** 恢复：只有管理员能恢复已删除的任务。 */
    @Transactional
    public void restore(User me, Long taskId) {
        if (!me.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        if (taskId == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        ScheduledTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        task.setIsDeleted(0);
        // 恢复一个「停用期间已过期」的重复任务，同样不要立刻补发
        advanceIfStale(task);
        taskRepository.save(task);
    }

    // ==================== 内部工具 ====================

    /**
     * 取任务并校验可见性：普通用户访问别人的任务、或访问已软删的任务，
     * 一律按「任务不存在」处理，避免泄露任务是否存在。
     */
    private ScheduledTask requireVisible(User me, Long taskId) {
        if (taskId == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        ScheduledTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        boolean deleted = task.getIsDeleted() != null && task.getIsDeleted() == 1;
        if (!me.isAdmin() && (!Objects.equals(task.getUserId(), me.getId()) || deleted)) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    private Specification<ScheduledTask> buildSpec(User me, String search, Integer status,
                                                   boolean allUsers, boolean includeDeleted) {
        boolean crossUser = me.isAdmin() && allUsers;
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!crossUser) {
                predicates.add(cb.equal(root.get("userId"), me.getId()));
            }
            if (me.isAdmin()) {
                predicates.add(cb.equal(root.get("isDeleted"), includeDeleted ? 1 : 0));
            } else {
                // 普通用户永远只看得到自己未删除的任务，includeDeleted 对其无效
                predicates.add(cb.equal(root.get("isDeleted"), 0));
            }
            if (StringUtils.hasText(search)) {
                // 与原型一致：在 task_data 里模糊匹配（主题、课程名、收件人都在这一段 JSON 里）
                predicates.add(cb.like(root.get("taskData"), "%" + search.trim() + "%"));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 解析时间字符串：接受 {@code yyyy-MM-dd HH:mm:ss}，也兼容 ISO 的 T 分隔与毫秒尾巴。 */
    private LocalDateTime parseTime(String text, boolean required) {
        if (!StringUtils.hasText(text)) {
            if (required) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请指定发送时间");
            }
            return LocalDateTime.now();
        }
        String value = text.trim().replace('T', ' ');
        if (value.length() > 19) {
            value = value.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(value, TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private void validateRepeat(String repeat, RepeatData data) {
        if (RepeatRuleService.REPEAT_WEEKLY.equals(repeat)
                && (data == null || data.daysOrEmpty().isEmpty())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "每周重复需至少选择一个星期");
        }
        if (RepeatRuleService.REPEAT_MONTHLY.equals(repeat)
                && (data == null || data.day() == null || data.day() < 1 || data.day() > 31)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的每月日期");
        }
    }

    private void validateMail(TaskData data) {
        if (!StringUtils.hasText(data.subject()) || !StringUtils.hasText(data.content())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件信息不完整");
        }
        AppProperties.Remind cfg = appProperties.getRemind();
        if (data.subject().length() > cfg.getMaxSubject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "邮件主题超过长度限制（" + cfg.getMaxSubject() + "字符）");
        }
        String format = data.format();
        if (!TaskData.FORMAT_TEXT.equals(format) && !TaskData.FORMAT_HTML.equals(format)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件格式仅支持 text 或 html");
        }
        int maxContent = data.isHtml() ? cfg.getMaxContentHtml() : cfg.getMaxContentText();
        if (data.content().length() > maxContent) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "邮件内容超过长度限制（" + maxContent + "字符）");
        }
    }

    private TaskData readJson(String json) {
        try {
            return objectMapper.readValue(json, TaskData.class).normalized();
        } catch (JsonProcessingException e) {
            // 库里数据被手工改坏时不让整个列表挂掉，退化成空内容
            log.warn("任务内容 JSON 解析失败，按空内容处理：{}", e.getMessage());
            return new TaskData(null, "", "", TaskData.FORMAT_TEXT, RepeatRuleService.REPEAT_NONE, null, "custom");
        }
    }

    private String writeJson(TaskData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务内容序列化失败");
        }
    }

    private TaskView toView(ScheduledTask task, String ownerUsername) {
        return new TaskView(task.getId(), task.getUserId(), task.getTaskType(), task.getStatus(),
                task.getScheduledTime(), task.getExecutedAt(), task.getCreatedAt(), task.getIsDeleted(),
                readJson(task.getTaskData()), ownerUsername);
    }

    private Map<Long, String> ownerNames(List<ScheduledTask> tasks) {
        Set<Long> ids = tasks.stream().map(ScheduledTask::getUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        userRepository.findAllById(ids).forEach(user -> names.put(user.getId(), user.getUsername()));
        return names;
    }

    private void writeLog(ScheduledTask task, boolean success, String triggerType, String message, LocalDateTime at) {
        TaskLog logRow = new TaskLog();
        logRow.setTaskId(task.getId());
        logRow.setUserId(task.getUserId());
        logRow.setExecutedAt(at);
        logRow.setSuccess(success ? 1 : 0);
        logRow.setTriggerType(triggerType);
        logRow.setMessage(message);
        taskLogRepository.save(logRow);
    }
}
