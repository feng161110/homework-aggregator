//平台 Cookie 状态
package com.xiaofu.student.entity;

public enum PlatformCookieStatus {

    /** 已校验通过，可正常使用。 */
    VALID,

    /** 已失效（校验不通过），需要用保存的账号密码自动重登或用户手动获取。 */
    EXPIRED,

    /** 尚未校验过（刚绑定、或保存后校验时平台服务不可用）。 */
    UNKNOWN
}
