package com.ahz.shizhi.model;

import com.ahz.shizhi.common.api.ErrorCode;

/**
 * 模型调用失败的统一出口。带上 {@link ErrorCode} 是为了让上层不用再翻译一遍——
 * 上游返回错误、限流、超时在 04 里是三个不同的码，前端的提示完全不同。
 */
public class ModelException extends RuntimeException {

    private final ErrorCode errorCode;

    public ModelException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ModelException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
