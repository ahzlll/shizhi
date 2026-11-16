package com.ahz.shizhi.auth.dto;

/**
 * 登录 / 续期的出参。
 *
 * <p>{@code expiresIn} 是 access token 的剩余秒数，前端据此提前续期，
 * 而不是等收到 401 再补救。
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {

    public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
