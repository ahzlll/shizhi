package com.ahz.shizhi.account;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import com.ahz.shizhi.account.dto.ProfileView;
import com.ahz.shizhi.account.dto.UpdateProfileRequest;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProfileService {

    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final ProfileRepository repository;

    public ProfileService(ProfileRepository repository) {
        this.repository = repository;
    }

    public ProfileView get(long userId) {
        return repository.find(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在"));
    }

    public ProfileView updateNickname(long userId, UpdateProfileRequest request) {
        String nickname = request.nickname() == null ? null : request.nickname().strip();
        // 空串等于清空昵称，统一存 null，避免出现「昵称是空字符串」这种中间态
        repository.updateNickname(userId, nickname == null || nickname.isEmpty() ? null : nickname);
        return get(userId);
    }

    public ProfileView saveAvatar(long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择图片");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "图片不能超过 2MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只支持 png、jpeg、webp");
        }
        try {
            repository.saveAvatar(userId, file.getBytes(), contentType);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
        return get(userId);
    }

    public void deleteAvatar(long userId) {
        repository.clearAvatar(userId);
    }

    public Optional<ProfileRepository.Avatar> avatar(long userId) {
        return repository.findAvatar(userId);
    }
}
