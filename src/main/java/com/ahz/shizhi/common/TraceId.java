package com.ahz.shizhi.common;

import org.slf4j.MDC;

/**
 * 请求级追踪标识。由 {@code TraceIdFilter} 写入 MDC，随响应头返回。
 */
public final class TraceId {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
