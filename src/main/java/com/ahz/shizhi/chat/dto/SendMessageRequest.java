package com.ahz.shizhi.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发消息的请求体。
 *
 * <p>只有 {@code content} 一个字段：要的上下文都在会话里，没必要让前端再传一遍。
 * 校验交给 {@code @NotBlank} 走 {@code MethodArgumentNotValidException}，
 * 所以在返回 SseEmitter 之前就会变成一条普通的 400 JSON——客户端发错东西时，
 * 拿到的不该是一个已经开了流的 SSE 连接。
 */
public record SendMessageRequest(
        @NotBlank(message = "不能为空") String content
) {
}
