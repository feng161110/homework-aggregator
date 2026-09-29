//错误码枚举
package com.xiaofu.student.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    OK(HttpStatus.OK, "成功"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "参数校验失败"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求不合法"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的接口或资源不存在"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    /** 登录请求参数中的时间戳与服务端相差超过允许窗口，判定为过期请求（防重放）。 */
    LOGIN_TIMEOUT(HttpStatus.REQUEST_TIMEOUT, "登录请求已超时，请重试"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "请先完成邮箱验证"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "账号已锁定，请稍后重试"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用，请更换"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "该邮箱已被注册"),
    TOKEN_INVALID(HttpStatus.BAD_REQUEST, "链接无效或已过期"),
    TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "链接已过期，请重新申请"),
    TOKEN_USED(HttpStatus.BAD_REQUEST, "该链接已被使用"),
    CODE_INVALID(HttpStatus.BAD_REQUEST, "验证码错误或已失效"),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "验证码已过期，请重新获取"),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请稍后再试"),
    EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "邮件发送失败，请稍后重试"),
    FILE_INVALID(HttpStatus.BAD_REQUEST, "文件格式不正确"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件过大，请压缩后重试"),
    DATA_CONFLICT(HttpStatus.CONFLICT, "数据冲突，请检查用户名或邮箱是否已被使用"),
    PLATFORM_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "不支持的平台"),
    PLATFORM_NOT_BOUND(HttpStatus.BAD_REQUEST, "该平台尚未绑定账号，请先登录一次"),
    PLATFORM_LOGIN_FAILED(HttpStatus.BAD_REQUEST, "平台登录失败，请检查账号密码"),
    PLATFORM_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "平台登录服务不可用，请确认本地服务已启动"),
    CRYPTO_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "凭证加密或解密失败"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "任务不存在"),
    TASK_DISABLED(HttpStatus.BAD_REQUEST, "任务不存在或已禁用"),
    ADMIN_ONLY(HttpStatus.FORBIDDEN, "仅管理员可执行该操作"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号已被禁用，如有疑问请联系管理员"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}