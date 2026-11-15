package com.ahz.shizhi.common.exception;

import com.ahz.shizhi.common.api.ErrorCode;

/**
 * 业务异常。抛出后由 {@code GlobalExceptionHandler} 转成对应的 HTTP 状态与业务码。
 *
 * <p>只用于「预期的失败」——参数不合规、资源不存在、状态不允许。真正的程序错误不要包成它，
 * 那样会掩盖堆栈。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
