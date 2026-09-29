///api/auth/* 全部接口
package com.xiaofu.student.controller;

import com.xiaofu.student.common.ApiResponse;
import com.xiaofu.student.config.AppProperties;
import com.xiaofu.student.dto.ChangePasswordRequest;
import com.xiaofu.student.dto.DeviceInfoResponse;
import com.xiaofu.student.dto.ForgotPasswordRequest;
import com.xiaofu.student.dto.LoginRequest;
import com.xiaofu.student.dto.RefreshTokenRequest;
import com.xiaofu.student.dto.RegisterRequest;
import com.xiaofu.student.dto.ResendVerificationRequest;
import com.xiaofu.student.dto.ResetPasswordByCodeRequest;
import com.xiaofu.student.dto.ResetPasswordRequest;
import com.xiaofu.student.dto.ResetTokenInfoResponse;
import com.xiaofu.student.dto.TokenResponse;
import com.xiaofu.student.dto.UserInfoResponse;
import com.xiaofu.student.dto.VerifyEmailRequest;
import com.xiaofu.student.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 浏览器携带 refresh token 的请求头，用于兜底识别「当前设备」。 */
    private static final String REFRESH_TOKEN_HEADER = "X-Refresh-Token";

    private final AuthService authService;
    private final AppProperties appProperties;

    @PostMapping("/register")
    public ApiResponse<String> register(@Valid @RequestBody RegisterRequest request,
                                        HttpServletRequest httpRequest) {
        return ApiResponse.okMessage(authService.register(request, clientIp(httpRequest)));
    }

    @PostMapping("/verify-email")
    public ApiResponse<TokenResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                                                  HttpServletRequest httpRequest) {
        // 验证成功即自动登录，直接返回令牌，前端携带令牌进入首页
        return ApiResponse.ok(authService.verifyEmail(request.email(), request.code(),
                clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    @PostMapping("/resend-verification")
    public ApiResponse<String> resendVerification(@Valid @RequestBody ResendVerificationRequest request,
                                                  HttpServletRequest httpRequest) {
        return ApiResponse.okMessage(authService.resendVerification(request.email(), clientIp(httpRequest)));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        return ApiResponse.ok(authService.login(request, ip, userAgent));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(Authentication authentication,
                                      @Valid @RequestBody RefreshTokenRequest request,
                                      HttpServletRequest httpRequest) {
        Long userId = (Long) authentication.getPrincipal();
        authService.logout(userId, request.refreshToken(), clientIp(httpRequest));
        return ApiResponse.okMessage("已退出登录");
    }

    @PostMapping("/forgot-password")
    public ApiResponse<String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.okMessage(authService.forgotPassword(request.email(), clientIp(httpRequest),
                frontendOrigin(httpRequest)));
    }

    @PostMapping("/reset-password")
    public ApiResponse<String> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                             HttpServletRequest httpRequest) {
        authService.resetPassword(request.token(), request.newPassword(), clientIp(httpRequest));
        return ApiResponse.okMessage("密码重置成功，请使用新密码登录");
    }

    /** 校验重置链接是否有效（进入重置页时先校验，失效则直接提示重新申请）。 */
    @GetMapping("/reset-password/check")
    public ApiResponse<ResetTokenInfoResponse> checkResetToken(@RequestParam("token") String token) {
        return ApiResponse.ok(authService.checkResetToken(token));
    }

    /** 登录状态下凭原密码修改密码。 */
    @PostMapping("/change-password")
    public ApiResponse<String> changePassword(Authentication authentication,
                                              @Valid @RequestBody ChangePasswordRequest request,
                                              HttpServletRequest httpRequest) {
        Long userId = (Long) authentication.getPrincipal();
        authService.changePassword(userId, request.oldPassword(), request.newPassword(), clientIp(httpRequest));
        return ApiResponse.okMessage("密码修改成功，请使用新密码重新登录");
    }

    /** 方式二：发送重置密码验证码（前端输入验证码直接改密）。 */
    @PostMapping("/forgot-password/code")
    public ApiResponse<String> sendPasswordResetCode(@Valid @RequestBody ForgotPasswordRequest request,
                                                     HttpServletRequest httpRequest) {
        return ApiResponse.okMessage(authService.sendPasswordResetCode(request.email(), clientIp(httpRequest)));
    }

    /** 方式二：验证码 + 新密码 直接重置。 */
    @PostMapping("/reset-password/code")
    public ApiResponse<String> resetPasswordByCode(@Valid @RequestBody ResetPasswordByCodeRequest request,
                                                   HttpServletRequest httpRequest) {
        authService.resetPasswordByCode(request.email(), request.code(), request.newPassword(),
                clientIp(httpRequest));
        return ApiResponse.okMessage("密码重置成功，请使用新密码登录");
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ApiResponse.ok(authService.me(userId));
    }

    @PostMapping("/avatar")
    public ApiResponse<UserInfoResponse> uploadAvatar(Authentication authentication,
                                                      @RequestParam("file") MultipartFile file) {
        Long userId = (Long) authentication.getPrincipal();
        return ApiResponse.ok(authService.updateAvatar(userId, file));
    }

    // ==================== 登录设备 ====================

    /** 当前账号的登录设备列表（其中 current=true 的为当前设备）。 */
    @GetMapping("/devices")
    public ApiResponse<List<DeviceInfoResponse>> devices(Authentication authentication,
                                                         HttpServletRequest httpRequest) {
        Long userId = (Long) authentication.getPrincipal();
        return ApiResponse.ok(authService.listDevices(userId, sessionId(authentication),
                httpRequest.getHeader(REFRESH_TOKEN_HEADER), clientIp(httpRequest),
                httpRequest.getHeader("User-Agent")));
    }

    /** 将指定设备下线。 */
    @DeleteMapping("/devices/{id}")
    public ApiResponse<String> revokeDevice(Authentication authentication, @PathVariable("id") Long id,
                                            HttpServletRequest httpRequest) {
        Long userId = (Long) authentication.getPrincipal();
        authService.revokeDevice(userId, id, clientIp(httpRequest));
        return ApiResponse.okMessage("该设备已退出登录");
    }

    /** 退出除当前设备外的其它设备。 */
    @PostMapping("/devices/revoke-others")
    public ApiResponse<String> revokeOtherDevices(Authentication authentication,
                                                  HttpServletRequest httpRequest) {
        Long userId = (Long) authentication.getPrincipal();
        int count = authService.revokeOtherDevices(userId, sessionId(authentication),
                httpRequest.getHeader(REFRESH_TOKEN_HEADER), clientIp(httpRequest));
        return ApiResponse.okMessage(count > 0 ? "已退出其它 " + count + " 台设备" : "没有其它在线设备");
    }

    /** 取当前请求所属的登录会话标识（来自 access token 的 sid 声明）。 */
    private String sessionId(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        return credentials instanceof String value ? value : null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 取触发请求的前端地址（Origin / Referer），用于生成邮件里的重置链接，
     * 避免 localhost 链接在手机等其它设备上打不开。
     * 只接受 CORS 白名单内的地址（支持 * 通配，如 http://192.168.*.*:5173），
     * 防止恶意来源被拼进邮件钓鱼；其余情况回退到配置的 base-url。
     */
    private String frontendOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if ((origin == null || origin.isBlank()) && request.getHeader("Referer") != null) {
            try {
                origin = URI.create(request.getHeader("Referer")).resolve("/").toString();
            } catch (IllegalArgumentException ignored) {
                origin = null;
            }
        }
        if (origin == null || !origin.startsWith("http")) {
            return null;
        }
        String normalized = origin.replaceAll("/+$", "");
        List<String> patterns = appProperties.getCors().getAllowedOrigins();
        if (patterns.contains(normalized)) {
            return normalized;
        }
        // 通配匹配仅对纯 IP 形式的来源生效，避免 192.168.evil.com 之类伪 IP 域名绕过
        boolean isIpOrigin = normalized.matches("https?://\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?");
        if (isIpOrigin && patterns.stream().anyMatch(p -> matchOriginPattern(normalized, p))) {
            return normalized;
        }
        return null;
    }

    /** 简化 ant 风格通配匹配：按 * 分段，逐段前后缀比对（如 http://192.168.*.*:5173）。 */
    private static boolean matchOriginPattern(String origin, String pattern) {
        if (!pattern.contains("*")) {
            return false;
        }
        String[] parts = pattern.split("\\*");
        int index = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (i == parts.length - 1 && !pattern.endsWith("*")) {
                // 末段必须匹配到结尾
                if (!origin.regionMatches(origin.length() - part.length(), part, 0, part.length())) {
                    return false;
                }
            } else {
                int found = origin.indexOf(part, index);
                if (found < 0) {
                    return false;
                }
                if (i == 0 && found != 0) {
                    return false; // 首段必须从头开始
                }
                index = found + part.length();
            }
        }
        return true;
    }
}