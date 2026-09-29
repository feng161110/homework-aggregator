//仅回传 Cookie（学习通浏览器助手 / 手动粘贴）
package com.xiaofu.student.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record PlatformCookieRequest(
        @Size(max = 128, message = "平台账号过长")
        String account,

        @NotNull(message = "cookies 不能为空")
        Map<String, String> cookies,

        Map<String, Object> extra,

        @Size(max = 16, message = "来源标识过长")
        String source
) {
}
