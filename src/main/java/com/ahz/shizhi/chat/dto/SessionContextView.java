package com.ahz.shizhi.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前会话的上下文占用。数字来自最近一次成功调用的 usage，比按字数估算准。
 *
 * @param model          这一轮实际使用的模型名（用户自带则显示他的模型）
 * @param usedTokens     上一轮送进模型 + 生成回来的 token 数，约等于模型眼里这段对话的大小
 * @param contextWindow  上下文上限，取自配置
 * @param percent        占用百分比，保留一位小数
 * @param custom         是否用的是用户自己的模型
 */
public record SessionContextView(
        @Schema(example = "deepseek-chat") String model,
        int usedTokens,
        int contextWindow,
        double percent,
        boolean custom
) {
}
