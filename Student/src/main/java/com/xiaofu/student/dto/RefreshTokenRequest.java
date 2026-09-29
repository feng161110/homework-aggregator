//刷新/登出
package com.xiaofu.student.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken
) {
}