//平台 Cookie 校验结果
package com.xiaofu.student.dto;

import java.util.Map;

public record PlatformCheckResponse(
        boolean valid,
        String message,
        String name,
        String avatar,
        String cookieStatus,
        Map<String, Object> userinfo
) {
}
