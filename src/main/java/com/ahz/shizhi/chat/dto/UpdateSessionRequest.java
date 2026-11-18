package com.ahz.shizhi.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSessionRequest(

        @Schema(example = "线程池与并发")
        @NotBlank(message = "不能为空")
        @Size(max = 60, message = "不能超过 60 个字符")
        String title
) {
}
