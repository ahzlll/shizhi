package com.ahz.shizhi.chat.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建会话的请求体。整个 body 是可选的——前端进聊天页就先建一个空会话，没有别的东西要传。
 *
 * <p>不传 {@code skillCode} 表示交给系统路由，所以这里不能设成必填。
 */
public record CreateSessionRequest(
        @Size(max = 32, message = "技能标识最长 32 个字符") String skillCode
) {
}
