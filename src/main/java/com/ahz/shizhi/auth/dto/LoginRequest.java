package com.ahz.shizhi.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参。{@code account} 既可以是用户名也可以是邮箱，不强制用户记哪个。
 */
public record LoginRequest(

        @NotBlank(message = "不能为空")
        String account,

        @NotBlank(message = "不能为空")
        String password
) {
}
