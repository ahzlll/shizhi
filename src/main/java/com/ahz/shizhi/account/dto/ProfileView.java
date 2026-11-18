package com.ahz.shizhi.account.dto;

import java.time.OffsetDateTime;

/**
 * @param avatarVersion 头像最后更新时间，null 表示没传过。前端拿它拼缓存参数，
 *                      换头像后 URL 变了，浏览器才会重新拉图
 */
public record ProfileView(
        long id,
        String username,
        String email,
        String nickname,
        OffsetDateTime avatarVersion,
        OffsetDateTime createdAt
) {
}
