package com.ahz.shizhi.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 历史消息对外的视图。
 *
 * <p>{@code streamState} 是判定消息状态的唯一依据，不能靠 {@code content} 为空反推——
 * 占位行和「模型返回了空回答」在正文上分不出来。
 *
 * <p>{@code refNoteIds} / {@code refConceptIds} 必须在历史里带出来。检索结果是生成前就落库的，
 * 目的正是让断线刷新后还能渲染「参考了你的 N 条笔记」；不带这两个字段，那次落库就白做了。
 *
 * <p>null 也参与序列化：{@code supersededBy} 恒为 null（被替代的行已被过滤掉），
 * {@code interruptReason} 对正常完成的消息为 null，字段消失会让前端少一条渲染分支的依据。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessageView(
        long id,
        String role,
        String content,
        int streamState,
        String interruptReason,
        Long supersededBy,
        List<Long> refNoteIds,
        List<Long> refConceptIds,
        OffsetDateTime createdAt
) {
}
