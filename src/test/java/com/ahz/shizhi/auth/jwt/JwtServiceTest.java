package com.ahz.shizhi.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-bytes!!";

    private JwtService service(Duration accessTtl) {
        return new JwtService(new JwtProperties(SECRET, accessTtl, Duration.ofDays(14), "shizhi"));
    }

    @Test
    void accessTokenRoundTrip() {
        JwtService jwt = service(Duration.ofMinutes(30));

        String token = jwt.issueAccessToken(42L, "ahz");
        JwtPayload payload = jwt.parseAccessToken(token);

        assertThat(payload.userId()).isEqualTo(42L);
        assertThat(payload.username()).isEqualTo("ahz");
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() {
        JwtService jwt = service(Duration.ofMinutes(30));
        String refresh = jwt.issueRefreshToken(42L, "ahz");

        assertThatThrownBy(() -> jwt.parseAccessToken(refresh))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() {
        JwtService jwt = service(Duration.ofMinutes(30));
        String access = jwt.issueAccessToken(42L, "ahz");

        assertThatThrownBy(() -> jwt.parseRefreshToken(access))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = service(Duration.ofMinutes(30));
        String token = jwt.issueAccessToken(42L, "ahz");
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> jwt.parseAccessToken(tampered))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void expiredTokenReportsExpiryNotGenericFailure() {
        JwtService jwt = service(Duration.ofSeconds(-1));
        String token = jwt.issueAccessToken(42L, "ahz");

        assertThatThrownBy(() -> jwt.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.TOKEN_EXPIRED));
    }

    @Test
    void shortSecretIsRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtService(
                new JwtProperties("too-short", Duration.ofMinutes(30), Duration.ofDays(14), "shizhi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
