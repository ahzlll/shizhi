package com.ahz.shizhi.auth.jwt;

/**
 * 从令牌里解析出来的身份。只带 id 和用户名，够用且不占空间。
 */
public record JwtPayload(long userId, String username) {
}
