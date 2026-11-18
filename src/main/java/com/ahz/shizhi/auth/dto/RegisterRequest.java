package com.ahz.shizhi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @Schema(example = "demo_01")
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "4~32 位，只能包含字母、数字、下划线")
        String username,

        @Schema(example = "demo01@test.com")
        @NotBlank(message = "不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "不能超过 128 个字符")
        String email,

        @Schema(example = "pass12345")
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$", message = "8~64 位，至少包含字母和数字")
        String password
) {
}
