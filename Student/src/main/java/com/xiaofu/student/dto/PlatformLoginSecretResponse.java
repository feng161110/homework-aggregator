//平台账号密码（明文，仅供前端调浏览器助手自动重登）
package com.xiaofu.student.dto;

/**
 * 前端调浏览器助手自动重新登录时，需要一次性的账号 + 明文密码。
 * <p>
 * 这是唯一会把密码明文带出服务端的接口，仅学习通开放：
 * 其余平台的自动重登由后端直连登录服务完成，不需要把密码交给前端。
 * 响应带 no-store，前端只允许在内存里使用，不得写入任何持久化存储或渲染到界面。
 */
public record PlatformLoginSecretResponse(
        String platform,
        boolean bound,
        String account,
        String password
) {
}
