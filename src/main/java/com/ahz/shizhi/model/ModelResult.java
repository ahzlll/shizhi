package com.ahz.shizhi.model;

/**
 * 一次模型调用的结果。只描述「模型说了多少、为什么停下、用的是哪个模型」。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param finishReason     结束原因，正常结束为 {@code stop}
 * @param model            <b>实际</b>用到的模型名：降级到备用模型时记的是备用模型，
 *                         成本统计要按真正跑过的那个算
 */
public record ModelResult(int promptTokens, int completionTokens, String finishReason, String model) {
}
