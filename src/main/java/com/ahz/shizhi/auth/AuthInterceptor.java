package com.ahz.shizhi.auth;

import java.util.Set;

import com.ahz.shizhi.auth.jwt.JwtPayload;
import com.ahz.shizhi.auth.jwt.JwtService;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 解析 {@code Authorization: Bearer <accessToken>}，把登录者放进请求属性。
 *
 * <p>免鉴权的只有注册、登录、续期和健康检查——{@code /api/auth/me} 不在其中，
 * 因为它存在的意义就是「拿令牌换用户信息」，不鉴权就没有意义。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = "shizhi.principal";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/health");

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少访问令牌");
        }

        JwtPayload payload = jwtService.parseAccessToken(header.substring(BEARER_PREFIX.length()).trim());
        request.setAttribute(PRINCIPAL_ATTRIBUTE, new UserPrincipal(payload.userId(), payload.username()));
        return true;
    }
}
