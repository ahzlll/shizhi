package com.ahz.shizhi.auth;

/**
 * 当前请求的登录者。由 {@link AuthInterceptor} 放入请求属性，控制器通过 {@code @CurrentUser} 取。
 */
public record UserPrincipal(long userId, String username) {
}
