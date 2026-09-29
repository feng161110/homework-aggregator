//平台绑定状态（列表用，不含任何密钥）
package com.xiaofu.student.dto;

import java.time.Instant;

public record PlatformStatusResponse(
        String platform,
        String name,
        boolean bound,
        String account,
        boolean hasPassword,
        String cookieStatus,
        String cookieSource,
        int cookieCount,
        Instant lastLoginAt,
        Instant lastCheckedAt
) {
}
