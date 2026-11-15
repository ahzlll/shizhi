package com.ahz.shizhi.common.api;

import java.util.List;

import com.ahz.shizhi.common.TraceId;

/**
 * 统一响应体。所有接口都返回这一层，业务数据放在 {@code data} 里。
 *
 * <p>{@code code = 0} 表示成功，非 0 的取值见 {@link ErrorCode}。
 * 参数校验失败时 {@code errors} 按字段逐条给出原因，其余情况为 null（不参与序列化）。
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        List<ParamError> errors
) {

    public static final int SUCCESS_CODE = 0;
    private static final String SUCCESS_MESSAGE = "ok";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, TraceId.current(), null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null, TraceId.current(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.message());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, List<ParamError> errors) {
        String text = (message == null || message.isBlank()) ? errorCode.message() : message;
        List<ParamError> detail = (errors == null || errors.isEmpty()) ? null : List.copyOf(errors);
        return new ApiResponse<>(errorCode.code(), text, null, TraceId.current(), detail);
    }
}
