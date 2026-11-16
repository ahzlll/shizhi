package com.ahz.shizhi.chat.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 会话对外的视图。新建会话和会话列表用的是同一个形状，前端不需要为两处写两套解析。
 *
 * <p>显式要求 null 也参与序列化：全局配置是 {@code non_null}，而 {@code title}
 * 在发出第一条消息之前确实是 null，字段直接消失会让前端分不清「没有标题」和「接口没给」。
 *
 * @param title          建会话时留空，发第一条消息时才截取问题前 30 字
 * @param skillCode      本次会话绑定的技能标识，null 表示由系统路由
 * @param messageCount   冗余计数，含占位行与被替代的行，仅用于粗略展示
 * @param lastActiveAt   会话列表的排序依据
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SessionView(
        long id,
        String title,
        String skillCode,
        int messageCount,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt
) {
}
