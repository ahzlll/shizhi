package com.ahz.shizhi.auth;

import com.ahz.shizhi.auth.dto.LoginRequest;
import com.ahz.shizhi.auth.dto.RegisterRequest;
import com.ahz.shizhi.auth.dto.TokenResponse;
import com.ahz.shizhi.auth.dto.UserProfile;
import com.ahz.shizhi.auth.jwt.JwtPayload;
import com.ahz.shizhi.auth.jwt.JwtService;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import com.ahz.shizhi.user.User;
import com.ahz.shizhi.user.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /**
     * 用户不存在时也拿它跑一次 BCrypt，让「账号不存在」和「密码错误」耗时接近。
     * 否则响应快慢本身就是个用户名枚举接口。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public UserProfile register(RegisterRequest request) {
        if (users.existsByUsername(request.username())) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        if (users.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_TAKEN);
        }

        try {
            long id = users.insert(request.username(), request.email(),
                    passwordEncoder.encode(request.password()));
            return new UserProfile(id, request.username(), request.email(), null);
        } catch (DuplicateKeyException ex) {
            // 两个请求同时过了上面的 exists 检查，唯一约束是最后一道防线。
            // 按被撞的约束名给出准确提示，别笼统说「已被占用」。
            String detail = String.valueOf(ex.getMostSpecificCause().getMessage());
            throw new BusinessException(detail.contains("uk_sys_user_email")
                    ? ErrorCode.EMAIL_TAKEN
                    : ErrorCode.USERNAME_TAKEN);
        }
    }

    public TokenResponse login(LoginRequest request) {
        UserRepository.Credential credential = users.findCredentialByAccount(request.account()).orElse(null);
        String hash = credential == null ? DUMMY_HASH : credential.passwordHash();
        boolean matched = passwordEncoder.matches(request.password(), hash);

        if (credential == null || !matched) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }

        User user = credential.user();
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        users.touchLastLogin(user.id());
        return issueTokens(user);
    }

    /**
     * 用 refresh token 换新 access token。refresh 本身不滚动换新——
     * 前端并发请求时，滚动会让后到的请求拿着已作废的令牌把自己踢下线。
     */
    public TokenResponse refresh(String refreshToken) {
        JwtPayload payload = jwtService.parseRefreshToken(refreshToken);
        User user = users.findById(payload.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        return TokenResponse.bearer(
                jwtService.issueAccessToken(user.id(), user.username()),
                refreshToken,
                jwtService.accessTokenTtlSeconds());
    }

    public UserProfile profile(long userId) {
        return users.findById(userId)
                .map(UserProfile::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private TokenResponse issueTokens(User user) {
        return TokenResponse.bearer(
                jwtService.issueAccessToken(user.id(), user.username()),
                jwtService.issueRefreshToken(user.id(), user.username()),
                jwtService.accessTokenTtlSeconds());
    }
}
