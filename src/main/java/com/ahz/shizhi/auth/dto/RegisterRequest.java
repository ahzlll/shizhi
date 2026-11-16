package com.ahz.shizhi.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "4~32 位，只能包含字母、数字、下划线")
        String username,

        @NotBlank(message = "不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "不能超过 128 个字符")
        String email,

        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$", message = "8~64 位，至少包含字母和数字")
        String password
) {
}
