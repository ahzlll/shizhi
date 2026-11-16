package com.ahz.shizhi.common.api;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。1xxx 参数与权限，2xxx 业务规则，5xxx 服务与外部依赖。
 *
 * <p>用 4 位而不是 3 位，是为了和 HTTP 状态码在日志里区分开。
 */
public enum ErrorCode {

    // 1xxx 参数与权限
    PARAM_INVALID(1000, "参数校验失败", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(1001, "资源不存在", HttpStatus.NOT_FOUND),
    UNAUTHORIZED(1002, "未登录或令牌无效", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(1003, "登录已过期，请重新登录", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(1004, "无权访问该资源", HttpStatus.FORBIDDEN),
    TOO_MANY_REQUESTS(1005, "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
    METHOD_NOT_ALLOWED(1006, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(1007, "请求体格式不支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    // 2xxx 业务
    USERNAME_TAKEN(2001, "用户名已被占用", HttpStatus.CONFLICT),
    EMAIL_TAKEN(2002, "邮箱已被占用", HttpStatus.CONFLICT),
    BAD_CREDENTIALS(2003, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    SESSION_NOT_FOUND(2004, "会话不存在", HttpStatus.NOT_FOUND),
    NOTE_NOT_FOUND(2005, "笔记不存在或已删除", HttpStatus.NOT_FOUND),
    CONCEPT_NOT_FOUND(2006, "知识点不存在", HttpStatus.NOT_FOUND),
    EDGE_NOT_FOUND(2007, "连线不存在", HttpStatus.NOT_FOUND),
    EDGE_SELF_LINK(2008, "不能把知识点连到自己", HttpStatus.BAD_REQUEST),
    MESSAGE_ALREADY_COMPLETED(2009, "该回答已完成，无需重试", HttpStatus.CONFLICT),
    RETRY_LIMIT_EXCEEDED(2010, "重试次数已达上限", HttpStatus.TOO_MANY_REQUESTS),
    MERGE_ALREADY_RESOLVED(2011, "待确认记录不存在或已处理", HttpStatus.CONFLICT),
    MERGE_CONTENT_EMPTY(2012, "合并稿不能为空", HttpStatus.BAD_REQUEST),
    NOTE_CONTENT_TOO_SHORT(2013, "笔记正文过短", HttpStatus.BAD_REQUEST),

    // 5xxx 服务与外部依赖
    INTERNAL_ERROR(5000, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    MODEL_CALL_FAILED(5001, "模型调用失败", HttpStatus.BAD_GATEWAY),
    MODEL_RATE_LIMITED(5002, "模型服务繁忙，请稍后再试", HttpStatus.SERVICE_UNAVAILABLE),
    MODEL_TIMEOUT(5003, "模型响应超时", HttpStatus.GATEWAY_TIMEOUT),
    EMBEDDING_FAILED(5004, "向量化失败", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_UNAVAILABLE(5005, "数据库暂不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(int code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus status() {
        return status;
    }
}
