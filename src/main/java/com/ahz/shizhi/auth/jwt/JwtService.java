package com.ahz.shizhi.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

/**
 * 令牌签发与解析。
 *
 * <p>access 与 refresh 用同一个密钥签，靠 {@code typ} 声明区分用途——
 * 否则拿 refresh token 就能当 access 用，长有效期等于白设。
 */
@Service
public class JwtService {

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_USERNAME = "uname";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("shizhi.jwt.secret 至少需要 32 个字节");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(long userId, String username) {
        return issue(userId, username, TYPE_ACCESS, properties.accessTtl());
    }

    public String issueRefreshToken(long userId, String username) {
        return issue(userId, username, TYPE_REFRESH, properties.refreshTtl());
    }

    private String issue(long userId, String username, String type, java.time.Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TYPE, type)
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public JwtPayload parseAccessToken(String token) {
        return parse(token, TYPE_ACCESS);
    }

    public JwtPayload parseRefreshToken(String token) {
        return parse(token, TYPE_REFRESH);
    }

    private JwtPayload parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "令牌类型不正确");
            }
            return new JwtPayload(Long.parseLong(claims.getSubject()), claims.get(CLAIM_USERNAME, String.class));
        } catch (ExpiredJwtException ex) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTtl().toSeconds();
    }
}
