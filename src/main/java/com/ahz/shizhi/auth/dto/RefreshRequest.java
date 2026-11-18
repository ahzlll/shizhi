package com.ahz.shizhi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(

        @Schema(example = "把登录接口返回的 refreshToken 粘贴到这里")
        @NotBlank(message = "不能为空")
        String refreshToken
) {
}
