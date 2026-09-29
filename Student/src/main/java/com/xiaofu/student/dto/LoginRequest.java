//登录
package com.xiaofu.student.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录入参。
 *
 * <p>字段名与明文含义已做混淆：{@code u} = 账号、{@code p} = 密码。
 * 每个字段都是「明文参数.时间戳(毫秒)」**整体加密**后的 hex，密文里看不到时间戳：
 * <ul>
 *   <li>{@code u}：前端 chunk.b.js 的 RC4 加密结果</li>
 *   <li>{@code p}：前端 chunk.a.js 的 RSA 加密结果（超长内容会分成多块、以空格分隔）</li>
 * </ul>
 * 真正的解密与时间戳校验在 {@code LoginPayloadCipher} 中完成，
 * 所以这里的长度上限只用于挡住明显异常的请求体。
 */
public record LoginRequest(
        @NotBlank(message = "账号不能为空")
        @Size(max = 4096, message = "账号参数不合法")
        String u,

        @NotBlank(message = "密码不能为空")
        @Size(max = 4096, message = "密码参数不合法")
        String p
) {
}
