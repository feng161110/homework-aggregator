//统一响应体
package com.xiaofu.student.common;

public record ApiResponse<T>(boolean success, String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "", data);
    }

    public static <T> ApiResponse<T> okMessage(String message) {
        return new ApiResponse<>(true, "OK", message, null);
    }

    /** 既要提示文案、又要带数据的场景（如创建任务返回 task_id）。 */
    public static <T> ApiResponse<T> okMessage(String message, T data) {
        return new ApiResponse<>(true, "OK", message, data);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.name(),
                message != null ? message : errorCode.getDefaultMessage(), null);
    }
}