//平台自动重新登录结果
package com.xiaofu.student.dto;

/**
 * needManual = true 表示无法自动重登，需要用户手动获取
 * （学习通必须靠浏览器助手；或库里没保存密码）。
 */
public record PlatformRefreshResponse(
        boolean ok,
        boolean needManual,
        String message,
        String name,
        String cookieStatus
) {
}
