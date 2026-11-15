package com.ahz.shizhi.common.api;

/**
 * 字段级错误。参数校验失败时按字段逐条返回，前端据此把提示显示在对应输入框旁边。
 *
 * <p>{@code field} 为 {@code null} 表示整条请求级别的错误（比如「开始时间不能晚于结束时间」），
 * 这类错误挂不到某个具体字段上。
 */
public record ParamError(String field, String message) {
}
