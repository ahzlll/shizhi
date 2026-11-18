package com.ahz.shizhi.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateModelConfigRequest(

        @Schema(example = "https://api.deepseek.com")
        @NotBlank(message = "不能为空")
        @Size(max = 200, message = "不能超过 200 个字符")
        @Pattern(regexp = "^https?://.+", message = "必须是 http:// 或 https:// 开头的地址")
        String baseUrl,

        @Schema(example = "deepseek-chat")
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "不能超过 64 个字符")
        String model,

        @Schema(example = "sk-xxxxxxxx")
        @NotBlank(message = "不能为空")
        @Size(max = 200, message = "不能超过 200 个字符")
        String apiKey
) {
}
