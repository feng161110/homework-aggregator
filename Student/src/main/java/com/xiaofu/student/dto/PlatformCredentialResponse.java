//平台凭证详情（不回传密码明文）
package com.xiaofu.student.dto;

import java.time.Instant;
import java.util.Map;

public record PlatformCredentialResponse(
        String platform,
        String name,
        boolean bound,
        String account,
        boolean hasPassword,
        String cookieStatus,
        String cookieSource,
        Map<String, String> cookies,
        Map<String, Object> extra,
        Instant lastLoginAt,
        Instant lastCheckedAt
) {
}
