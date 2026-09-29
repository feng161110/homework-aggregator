//前端登录平台成功后回传的凭证
package com.xiaofu.student.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * password 可为空：为空时不覆盖库中已保存的密码。
 * source 标记凭证来源：password（账号密码登录）/ plugin（浏览器助手）。
 */
public record PlatformBindRequest(
        @NotBlank(message = "平台账号不能为空")
        @Size(max = 128, message = "平台账号过长")
        String account,

        @Size(max = 256, message = "平台密码过长")
        String password,

        Map<String, String> cookies,

        Map<String, Object> extra,

        @Size(max = 16, message = "来源标识过长")
        String source
) {
}
