package com.ahz.shizhi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 真起一个 HTTP 服务跑完整链路：注册 → 登录 → 带令牌取用户 → 未带令牌被拦。
 *
 * <p>用固定端口 + JDK HttpClient，而不是 MockMvc，避免依赖可能变动的测试自动配置包。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18090")
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:18090";

    private record Resp(int status, String body) {
    }

    @Test
    void registerLoginAndAccessProtectedEndpoint() throws Exception {
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";

        Resp registered = post("/api/auth/register", """
                {"username":"%s","email":"%s","password":"password123"}
                """.formatted(username, email), null);
        assertThat(registered.status()).isEqualTo(200);
        assertThat(registered.body()).contains("\"code\":0").contains(username);

        Resp duplicated = post("/api/auth/register", """
                {"username":"%s","email":"%s","password":"password123"}
                """.formatted(username, email), null);
        assertThat(duplicated.status()).isEqualTo(409);
        assertThat(duplicated.body()).contains("\"code\":2001");

        Resp login = post("/api/auth/login", """
                {"account":"%s","password":"password123"}
                """.formatted(username), null);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body()).contains("\"tokenType\":\"Bearer\"");

        String accessToken = jsonString(login.body(), "accessToken");
        assertThat(accessToken).isNotBlank();

        Resp me = get("/api/auth/me", accessToken);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains(username).contains(email);

        Resp anonymous = get("/api/auth/me", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).contains("\"code\":1002");

        Resp wrongPassword = post("/api/auth/login", """
                {"account":"%s","password":"wrong-password"}
                """.formatted(username), null);
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.body()).contains("\"code\":2003");
    }

    @Test
    void refreshExchangesForNewAccessToken() throws Exception {
        String username = "r" + UUID.randomUUID().toString().substring(0, 8);
        post("/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"password123"}
                """.formatted(username, username), null);

        Resp login = post("/api/auth/login", """
                {"account":"%s","password":"password123"}
                """.formatted(username), null);
        String refreshToken = jsonString(login.body(), "refreshToken");

        Resp refreshed = post("/api/auth/refresh", """
                {"refreshToken":"%s"}
                """.formatted(refreshToken), null);
        assertThat(refreshed.status()).isEqualTo(200);

        String newAccess = jsonString(refreshed.body(), "accessToken");
        assertThat(newAccess).isNotBlank();
        // 令牌的签发时间只精确到秒，登录和续期落进同一秒时，签出来的是逐字节相同的令牌。
        // 所以这里验的不是「两个字符串必须不同」，而是续期真的换到了一个能用的令牌，
        // 且 refresh 令牌原样返回（02 D-12：refresh 不滚动，否则并发请求会互相踢下线）。
        assertThat(get("/api/auth/me", newAccess).status()).isEqualTo(200);
        assertThat(jsonString(refreshed.body(), "refreshToken")).isEqualTo(refreshToken);
    }

    @Test
    void invalidRegistrationReturnsFieldLevelErrors() throws Exception {
        Resp resp = post("/api/auth/register", """
                {"username":"ab","email":"not-an-email","password":"123"}
                """, null);

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body())
                .contains("\"code\":1000")
                .contains("\"errors\"")
                .contains("\"username\"")
                .contains("\"email\"")
                .contains("\"password\"");
    }

    private Resp post(String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private Resp get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private static String jsonString(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }
}
