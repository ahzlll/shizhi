package com.ahz.shizhi.account;

import java.util.concurrent.TimeUnit;

import com.ahz.shizhi.account.dto.ModelConfigView;
import com.ahz.shizhi.account.dto.ProfileView;
import com.ahz.shizhi.account.dto.UpdateModelConfigRequest;
import com.ahz.shizhi.account.dto.UpdateProfileRequest;
import com.ahz.shizhi.auth.CurrentUser;
import com.ahz.shizhi.auth.UserPrincipal;
import com.ahz.shizhi.common.api.ApiResponse;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final UserModelConfigService modelConfigs;
    private final ProfileService profiles;

    public AccountController(UserModelConfigService modelConfigs, ProfileService profiles) {
        this.modelConfigs = modelConfigs;
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    public ApiResponse<ProfileView> profile(@Parameter(hidden = true) @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(profiles.get(principal.userId()));
    }

    @PutMapping("/profile")
    public ApiResponse<ProfileView> updateProfile(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                                  @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(profiles.updateNickname(principal.userId(), request));
    }

    @PostMapping("/avatar")
    public ApiResponse<ProfileView> uploadAvatar(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                                 @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(profiles.saveAvatar(principal.userId(), file));
    }

    @DeleteMapping("/avatar")
    public ApiResponse<ProfileView> deleteAvatar(@Parameter(hidden = true) @CurrentUser UserPrincipal principal) {
        profiles.deleteAvatar(principal.userId());
        return ApiResponse.ok(profiles.get(principal.userId()));
    }

    /**
     * 头像读取不鉴权：{@code <img src>} 带不上 Authorization 头。
     * 只放行读取，上传与删除仍然要登录。
     */
    @GetMapping("/avatar/{userId}")
    public ResponseEntity<byte[]> avatar(@PathVariable long userId) {
        ProfileRepository.Avatar avatar = profiles.avatar(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "还没有上传头像"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(avatar.data());
    }

    @GetMapping("/model")
    public ApiResponse<ModelConfigView> getModelConfig(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(modelConfigs.view(principal.userId()));
    }

    @PutMapping("/model")
    public ApiResponse<ModelConfigView> saveModelConfig(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateModelConfigRequest request) {
        return ApiResponse.ok(modelConfigs.save(principal.userId(), request));
    }

    @DeleteMapping("/model")
    public ApiResponse<Void> deleteModelConfig(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal) {
        modelConfigs.delete(principal.userId());
        return ApiResponse.ok(null);
    }
}
