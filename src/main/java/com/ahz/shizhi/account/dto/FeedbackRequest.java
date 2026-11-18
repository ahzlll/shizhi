package com.ahz.shizhi.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(

        @Schema(example = "希望回答能导出成 Markdown")
        @NotBlank(message = "不能为空")
        @Size(max = 1000, message = "不能超过 1000 个字符")
        String content,

        @Schema(example = "you@example.com")
        @Size(max = 128, message = "不能超过 128 个字符")
        String contact,

        @Schema(example = "/chat")
        @Size(max = 200, message = "不能超过 200 个字符")
        String page
) {
}
