package com.ahz.shizhi.chat.dto;

/**
 * SSE 事件的数据体。
 *
 * <p>用一个 sealed 接口把四种事件收在一处：事件名和数据体是一一对应的，
 * 分散成四个文件只会让"加一个事件"变成"改四个地方"。
 *
 * <p>字段名直接就是 JSON 字段名（record 组件名），改名字就等于改接口。
 */
public sealed interface StreamEvent {

    /** 第一个事件，前端据此把本地消息对象和库里的行对上——没有它，"重试"这个能力就悬空了。 */
    record Ack(long userMessageId, long assistantMessageId, int streamState) implements StreamEvent {
    }

    record Delta(String text) implements StreamEvent {
    }

    record Done(long messageId, int promptTokens, int completionTokens, long elapsedMs,
                String finishReason, String model)
            implements StreamEvent {
    }

    record Error(long messageId, int code, String message) implements StreamEvent {
    }
}
