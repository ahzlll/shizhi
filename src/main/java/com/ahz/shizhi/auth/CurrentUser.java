package com.ahz.shizhi.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前登录者。用法：{@code public ApiResponse<UserProfile> me(@CurrentUser UserPrincipal user)}。
 *
 * <p>只有被鉴权拦截器保护过的接口才能拿到值；免鉴权接口上用它只会得到 401。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
