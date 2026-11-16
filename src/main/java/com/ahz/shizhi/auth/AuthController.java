package com.ahz.shizhi.auth;

import com.ahz.shizhi.auth.dto.LoginRequest;
import com.ahz.shizhi.auth.dto.RefreshRequest;
import com.ahz.shizhi.auth.dto.RegisterRequest;
import com.ahz.shizhi.auth.dto.TokenResponse;
import com.ahz.shizhi.auth.dto.UserProfile;
import com.ahz.shizhi.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<UserProfile> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    /**
     * 前端刷新页面后拿令牌换用户信息，用来恢复登录态。
     * 没有这个接口，前端只能把用户信息塞进 localStorage——令牌过期了也看不出来。
     */
    @GetMapping("/me")
    public ApiResponse<UserProfile> me(@CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(authService.profile(principal.userId()));
    }
}
