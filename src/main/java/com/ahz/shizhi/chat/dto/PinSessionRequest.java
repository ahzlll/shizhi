package com.ahz.shizhi.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record PinSessionRequest(

        @Schema(description = "true 置顶，false 取消置顶")
        @NotNull(message = "不能为空")
        Boolean pinned
) {
}
