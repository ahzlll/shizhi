package com.ahz.shizhi.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record BatchSessionRequest(

        @Schema(description = "会话 id，一次最多 100 个")
        @NotEmpty(message = "至少选一个会话")
        @Size(max = 100, message = "一次最多处理 100 个会话")
        List<Long> ids
) {
}
