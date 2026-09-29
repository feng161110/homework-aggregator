//管理后台接口
package com.xiaofu.student.controller;

import com.xiaofu.student.common.ApiResponse;
import com.xiaofu.student.dto.AdminUserDetailResponse;
import com.xiaofu.student.dto.AdminUserListResponse;
import com.xiaofu.student.entity.User;
import com.xiaofu.student.security.CurrentUserProvider;
import com.xiaofu.student.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台：看用户、看登录设备、禁用账号、踢设备下线。
 *
 * <p>所有接口都要求登录（SecurityConfig 的 anyRequest().authenticated()），
 * 并且必须是管理员 —— 这一层判断在 {@link AdminService} 里（项目没开方法级注解）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final CurrentUserProvider currentUserProvider;

    /** 用户列表：page / limit / search（用户名或邮箱）/ status（PENDING|ACTIVE|LOCKED|DISABLED）。 */
    @GetMapping("/users")
    public ApiResponse<AdminUserListResponse> users(Authentication authentication,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int limit,
                                                    @RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String status) {
        User me = currentUserProvider.requireAdmin(authentication);
        return ApiResponse.ok(adminService.list(me, page, limit, search, status));
    }

    /** 用户详情 + 全部登录记录（含已下线）。 */
    @GetMapping("/users/{id}")
    public ApiResponse<AdminUserDetailResponse> detail(Authentication authentication,
                                                       @PathVariable("id") Long id) {
        User me = currentUserProvider.requireAdmin(authentication);
        return ApiResponse.ok(adminService.detail(me, id));
    }

    /** 禁用账号：同时踢下线该账号的所有设备。 */
    @PostMapping("/users/{id}/disable")
    public ApiResponse<String> disable(Authentication authentication, @PathVariable("id") Long id,
                                       HttpServletRequest request) {
        User me = currentUserProvider.requireAdmin(authentication);
        return ApiResponse.okMessage(adminService.setDisabled(me, id, true, clientIp(request)));
    }

    /** 解除禁用。邮箱未验证的账号会回到「待验证」，不会直接变成可用。 */
    @PostMapping("/users/{id}/enable")
    public ApiResponse<String> enable(Authentication authentication, @PathVariable("id") Long id,
                                      HttpServletRequest request) {
        User me = currentUserProvider.requireAdmin(authentication);
        return ApiResponse.okMessage(adminService.setDisabled(me, id, false, clientIp(request)));
    }

    /** 踢下线该账号的全部设备（不禁用账号）。 */
    @PostMapping("/users/{id}/sessions/revoke")
    public ApiResponse<String> revokeSessions(Authentication authentication, @PathVariable("id") Long id,
                                              HttpServletRequest request) {
        User me = currentUserProvider.requireAdmin(authentication);
        return ApiResponse.okMessage(adminService.revokeUserSessions(me, id, clientIp(request)));
    }

    /** 踢下线单个登录设备。 */
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<String> revokeSession(Authentication authentication, @PathVariable("id") Long id,
                                             HttpServletRequest request) {
        User me = currentUserProvider.requireAdmin(authentication);
        return ApiResponse.okMessage(adminService.revokeSession(me, id, clientIp(request)));
    }

    /** 与 AuthController 相同的取 IP 方式：网关/Nginx 透传 X-Forwarded-For。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
