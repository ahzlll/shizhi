package com.ahz.shizhi.model;

/**
 * 一次模型调用的结果。只描述"模型说了多少、为什么停下"，不关心内容——
 * 内容已经通过 {@link ChatModelClient#stream} 的回调交付出去了。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param finishReason     结束原因，正常结束为 {@code stop}
 */
public record ModelResult(int promptTokens, int completionTokens, String finishReason) {
}
