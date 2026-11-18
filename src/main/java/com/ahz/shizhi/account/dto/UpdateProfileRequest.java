package com.ahz.shizhi.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        @Schema(example = "小林")
        @Size(max = 32, message = "不能超过 32 个字符")
        String nickname
) {
}
