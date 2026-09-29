//作业提醒 / 自定义提醒任务接口
package com.xiaofu.student.controller;

import com.xiaofu.student.common.ApiResponse;
import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.dto.TaskActionRequest;
import com.xiaofu.student.entity.User;
import com.xiaofu.student.security.CurrentUserProvider;
import com.xiaofu.student.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 任务接口，对应原型 tasks.php?action=... 的分发方式：
 * 读操作走 GET，写操作走 POST（body 里带 action）。
 *
 * <p>所有接口都需要登录（SecurityConfig 里 anyRequest().authenticated()），
 * 任务可见范围由 TaskService 按「普通用户只看自己的」过滤。
 */
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final CurrentUserProvider currentUserProvider;

    /** GET /api/task?action=list|detail|stats */
    @GetMapping
    public ApiResponse<?> get(Authentication authentication,
                              @RequestParam(defaultValue = "list") String action,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "10") int limit,
                              @RequestParam(required = false) String search,
                              @RequestParam(required = false) Integer status,
                              @RequestParam(defaultValue = "false") boolean allUsers,
                              @RequestParam(defaultValue = "false") boolean includeDeleted,
                              @RequestParam(required = false) Long id) {
        User me = currentUserProvider.require(authentication);
        return switch (action) {
            case "list" -> ApiResponse.ok(
                    taskService.list(me, page, limit, search, status, allUsers, includeDeleted));
            case "detail" -> ApiResponse.ok(taskService.detail(me, id));
            case "stats" -> ApiResponse.ok(taskService.stats(me, allUsers));
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的操作：" + action);
        };
    }

    /** POST /api/task：创建 / 立即发送 / 禁用 / 启用 / 改规则 / 删除 / 恢复。 */
    @PostMapping
    public ApiResponse<?> post(Authentication authentication, @RequestBody TaskActionRequest request) {
        User me = currentUserProvider.require(authentication);
        String action = request.action() == null ? "" : request.action();
        return switch (action) {
            case "create" -> {
                Long taskId = taskService.create(me, request);
                yield ApiResponse.okMessage("任务创建成功", Map.of("task_id", taskId));
            }
            case "execute" -> {
                taskService.execute(request.taskId(), me.getId(), me.isAdmin(), false);
                yield ApiResponse.okMessage("已发送");
            }
            case "cancel" -> {
                taskService.cancel(me, request.taskId());
                yield ApiResponse.okMessage("已禁用，到点后不会发送");
            }
            case "enable" -> {
                taskService.enable(me, request.taskId());
                yield ApiResponse.okMessage("已启用");
            }
            case "update" -> {
                taskService.update(me, request);
                yield ApiResponse.okMessage("已更新发送规则");
            }
            case "delete" -> {
                taskService.delete(me, request.taskId());
                yield ApiResponse.okMessage("已删除");
            }
            case "restore" -> {
                taskService.restore(me, request.taskId());
                yield ApiResponse.okMessage("已恢复");
            }
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的操作：" + action);
        };
    }
}
