///api/platform/* 平台凭证接口
package com.xiaofu.student.controller;

import com.xiaofu.student.common.ApiResponse;
import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.dto.PlatformBindRequest;
import com.xiaofu.student.dto.PlatformCheckResponse;
import com.xiaofu.student.dto.PlatformCookieRequest;
import com.xiaofu.student.dto.PlatformCoursesResponse;
import com.xiaofu.student.dto.PlatformCredentialResponse;
import com.xiaofu.student.dto.PlatformLinksResponse;
import com.xiaofu.student.dto.PlatformLoginSecretResponse;
import com.xiaofu.student.dto.PlatformRefreshResponse;
import com.xiaofu.student.dto.PlatformStatusResponse;
import com.xiaofu.student.entity.PlatformType;
import com.xiaofu.student.service.PlatformAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformAccountService platformAccountService;

    /** 三个平台的绑定状态（含未绑定的）。 */
    @GetMapping
    public ApiResponse<List<PlatformStatusResponse>> list(Authentication authentication) {
        return ApiResponse.ok(platformAccountService.list(userId(authentication)));
    }

    /** 单个平台的凭证详情（含 Cookie，不含密码）。 */
    @GetMapping("/{platform}")
    public ApiResponse<PlatformCredentialResponse> detail(Authentication authentication,
                                                          @PathVariable String platform) {
        return ApiResponse.ok(platformAccountService.detail(userId(authentication), parse(platform)));
    }

    /** 前端用账号密码登录成功后回传凭证入库。 */
    @PostMapping("/{platform}")
    public ApiResponse<PlatformCredentialResponse> bind(Authentication authentication,
                                                        @PathVariable String platform,
                                                        @Valid @RequestBody PlatformBindRequest request) {
        return ApiResponse.ok(platformAccountService.bind(userId(authentication), parse(platform), request));
    }

    /** 只回传 Cookie（学习通浏览器助手 / 手动粘贴）。 */
    @PutMapping("/{platform}/cookies")
    public ApiResponse<PlatformCredentialResponse> saveCookies(Authentication authentication,
                                                               @PathVariable String platform,
                                                               @Valid @RequestBody PlatformCookieRequest request) {
        return ApiResponse.ok(platformAccountService.saveCookies(userId(authentication), parse(platform), request));
    }

    /**
     * 取平台账号 + 明文密码，供前端调用浏览器助手自动重新登录时一次性使用。
     * 仅学习通开放；响应标记 no-store，避免密码被浏览器缓存。
     */
    @GetMapping("/{platform}/secret")
    public ResponseEntity<ApiResponse<PlatformLoginSecretResponse>> secret(Authentication authentication,
                                                                          @PathVariable String platform) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(platformAccountService.secret(userId(authentication), parse(platform))));
    }

    /** 用库里的 Cookie 校验是否仍然有效。 */
    @PostMapping("/{platform}/check")
    public ApiResponse<PlatformCheckResponse> check(Authentication authentication,
                                                    @PathVariable String platform) {
        return ApiResponse.ok(platformAccountService.check(userId(authentication), parse(platform)));
    }

    /** Cookie 失效后触发自动重新登录（学习通返回 needManual）。 */
    @PostMapping("/{platform}/refresh")
    public ApiResponse<PlatformRefreshResponse> refresh(Authentication authentication,
                                                        @PathVariable String platform) {
        return ApiResponse.ok(platformAccountService.refresh(userId(authentication), parse(platform)));
    }

    /**
     * 取平台的作业 / 考试链接（学习通 / 智慧树）。
     * 返回的 url 为该条的直达地址，按 courseId 分组。
     */
    @GetMapping("/{platform}/desktop-links")
    public ApiResponse<PlatformLinksResponse> desktopLinks(Authentication authentication,
                                                           @PathVariable String platform) {
        return ApiResponse.ok(platformAccountService.desktopLinks(userId(authentication), parse(platform)));
    }

    /**
     * 取平台的课程列表（学习通 / 雨课堂 / 智慧树）。
     * 每门课带默认入口 url；学习通另带任务 / 章节 / 作业 / 考试等各 pageHeader 入口。
     */
    @GetMapping("/{platform}/courses")
    public ApiResponse<PlatformCoursesResponse> courses(Authentication authentication,
                                                        @PathVariable String platform) {
        return ApiResponse.ok(platformAccountService.courses(userId(authentication), parse(platform)));
    }

    /** 解绑该平台。 */
    @DeleteMapping("/{platform}")
    public ApiResponse<String> unbind(Authentication authentication,
                                      @PathVariable String platform) {
        PlatformType type = parse(platform);
        platformAccountService.unbind(userId(authentication), type);
        return ApiResponse.okMessage("已解绑" + type.getDisplayName());
    }

    private Long userId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }

    private PlatformType parse(String platform) {
        return PlatformType.find(platform)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_SUPPORTED));
    }
}
