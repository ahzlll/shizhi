package com.ahz.shizhi.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(

        @NotBlank(message = "不能为空")
        String refreshToken
) {
}
