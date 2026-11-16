package com.ahz.shizhi.auth.jwt;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。密钥至少 32 字节，否则 HMAC-SHA256 会直接拒绝。
 *
 * <p>开发环境在 application-dev.yml 里给了默认值，生产必须从环境变量注入。
 */
@ConfigurationProperties(prefix = "shizhi.jwt")
public record JwtProperties(
        String secret,
        Duration accessTtl,
        Duration refreshTtl,
        String issuer
) {

    public JwtProperties {
        if (accessTtl == null) {
            accessTtl = Duration.ofMinutes(30);
        }
        if (refreshTtl == null) {
            refreshTtl = Duration.ofDays(14);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "shizhi";
        }
    }
}
