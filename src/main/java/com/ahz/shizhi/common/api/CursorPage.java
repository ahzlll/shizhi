package com.ahz.shizhi.common.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 游标式分页的返回体。用于会持续追加的数据（消息流），页码分页在这种场景下会漏读和重读。
 *
 * <p>{@code nextCursor} 为 null 表示没有更早的数据了，{@code hasMore} 与它同真同假，
 * 分两个字段是因为前端做「加载更多」时要判断的是布尔，而发请求要的是游标。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CursorPage<T>(
        List<T> list,
        Long nextCursor,
        boolean hasMore
) {
}
