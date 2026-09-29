//重置链接校验结果（邮箱脱敏，避免泄露）
package com.xiaofu.student.dto;

public record ResetTokenInfoResponse(
        /** 脱敏后的邮箱，如 z***n@qq.com */
        String email,
        /** 链接过期时间（ISO-8601） */
        String expiresAt
) {
}
