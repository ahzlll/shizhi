package com.ahz.shizhi.common.api;

import java.util.List;

/**
 * 页码式分页的返回体。{@code page} 从 1 开始。
 *
 * <p>抽出来是为了笔记列表、知识点列表复用同一个形状，前端只写一份分页组件。
 */
public record PageResult<T>(
        long total,
        int page,
        int size,
        List<T> list
) {
}
