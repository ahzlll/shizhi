package com.ahz.shizhi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import com.ahz.shizhi.auth.dto.LoginRequest;
import com.ahz.shizhi.auth.dto.RegisterRequest;
import com.ahz.shizhi.auth.dto.TokenResponse;
import com.ahz.shizhi.auth.jwt.JwtProperties;
import com.ahz.shizhi.auth.jwt.JwtService;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import com.ahz.shizhi.user.User;
import com.ahz.shizhi.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock
    private UserRepository users;

    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        jwtService = new JwtService(
                new JwtProperties("unit-test-secret-key-at-least-32-bytes!!", Duration.ofMinutes(30),
                        Duration.ofDays(14), "shizhi"));
        authService = new AuthService(users, passwordEncoder, jwtService);
    }

    @Test
    void registerHashesPasswordAndReturnsProfile() {
        when(users.existsByUsername("ahz")).thenReturn(false);
        when(users.existsByEmail("ahz@example.com")).thenReturn(false);
        when(users.insert(anyString(), anyString(), anyString())).thenReturn(7L);

        var profile = authService.register(
                new RegisterRequest("ahz", "ahz@example.com", "password123"));

        assertThat(profile.id()).isEqualTo(7L);
        assertThat(profile.username()).isEqualTo("ahz");
    }

    @Test
    void registerRejectsTakenUsername() {
        when(users.existsByUsername("ahz")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("ahz", "ahz@example.com", "password123")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.USERNAME_TAKEN));
    }

    @Test
    void loginReturnsTokenPair() {
        String hash = passwordEncoder.encode("password123");
        when(users.findCredentialByAccount("ahz"))
                .thenReturn(Optional.of(new UserRepository.Credential(
                        new User(7L, "ahz", "ahz@example.com", null, 1), hash)));

        TokenResponse tokens = authService.login(new LoginRequest("ahz", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(1800);
        verify(users).touchLastLogin(7L);
    }

    @Test
    void loginWithWrongPasswordIsIndistinguishableFromUnknownAccount() {
        String hash = passwordEncoder.encode("password123");
        when(users.findCredentialByAccount("ahz"))
                .thenReturn(Optional.of(new UserRepository.Credential(
                        new User(7L, "ahz", "ahz@example.com", null, 1), hash)));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ahz", "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.BAD_CREDENTIALS));
    }

    @Test
    void loginWithUnknownAccountUsesSameErrorCode() {
        when(users.findCredentialByAccount("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "password123")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.BAD_CREDENTIALS));
        verify(users, never()).touchLastLogin(any(Long.class));
    }

    @Test
    void disabledAccountCannotLogin() {
        String hash = passwordEncoder.encode("password123");
        when(users.findCredentialByAccount("ahz"))
                .thenReturn(Optional.of(new UserRepository.Credential(
                        new User(7L, "ahz", "ahz@example.com", null, 0), hash)));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ahz", "password123")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void refreshIssuesNewAccessTokenForActiveUser() {
        String refresh = jwtService.issueRefreshToken(7L, "ahz");
        when(users.findById(7L)).thenReturn(Optional.of(
                new User(7L, "ahz", "ahz@example.com", null, 1)));

        TokenResponse tokens = authService.refresh(refresh);

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isEqualTo(refresh);
        assertThat(jwtService.parseAccessToken(tokens.accessToken()).userId()).isEqualTo(7L);
    }

    @Test
    void refreshRejectsAccessToken() {
        String access = jwtService.issueAccessToken(7L, "ahz");

        assertThatThrownBy(() -> authService.refresh(access))
                .isInstanceOf(BusinessException.class);
    }
}
