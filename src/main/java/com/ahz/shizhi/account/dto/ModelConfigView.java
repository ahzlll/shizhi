package com.ahz.shizhi.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 给前端看的配置。永远不带明文 key，最多回显后四位用来确认「填的是哪一把」。
 */
public record ModelConfigView(
        @Schema(description = "服务端是否具备加密能力；为 false 时整个功能不可用")
        boolean enabled,
        boolean configured,
        String baseUrl,
        String model,
        String apiKeyMask
) {
}
