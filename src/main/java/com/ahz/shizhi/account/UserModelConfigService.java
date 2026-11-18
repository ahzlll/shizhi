package com.ahz.shizhi.account;

import java.util.Optional;

import com.ahz.shizhi.account.dto.ModelConfigView;
import com.ahz.shizhi.account.dto.UpdateModelConfigRequest;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.crypto.SecretCipher;
import com.ahz.shizhi.common.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 用户自带的模型配置。对外只给掩码，明文 key 只在 {@link #resolve} 里解出来交给调用方。
 */
@Service
public class UserModelConfigService {

    private final UserModelConfigRepository repository;
    private final SecretCipher cipher;

    public UserModelConfigService(UserModelConfigRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public ModelConfigView view(long userId) {
        if (!cipher.enabled()) {
            return new ModelConfigView(false, false, null, null, null);
        }
        return repository.find(userId)
                .map(config -> new ModelConfigView(true, true, config.baseUrl(), config.model(),
                        maskFromHint(config.apiKeyHint())))
                .orElseGet(() -> new ModelConfigView(true, false, null, null, null));
    }

    public ModelConfigView save(long userId, UpdateModelConfigRequest request) {
        if (!cipher.enabled()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "服务端未配置 shizhi.security.secret-key，暂时无法保存密钥");
        }
        String apiKey = request.apiKey().trim();
        repository.upsert(userId, stripTrailingSlash(request.baseUrl().trim()), request.model().trim(),
                cipher.encrypt(apiKey), lastFour(apiKey));
        return view(userId);
    }

    public void delete(long userId) {
        repository.delete(userId);
    }

    /** 生成时用：解出明文 key。没配或解不开都当作「没有自带配置」，回落到服务端默认模型。 */
    public Optional<ResolvedModel> resolve(long userId) {
        if (!cipher.enabled()) {
            return Optional.empty();
        }
        return repository.find(userId).flatMap(config -> {
            try {
                return Optional.of(new ResolvedModel(
                        config.baseUrl(), config.model(), cipher.decrypt(config.apiKeyCipher())));
            } catch (IllegalStateException ex) {
                // 主密钥轮换过，旧密文解不开：不能因为一条坏配置让整个对话不可用
                return Optional.empty();
            }
        });
    }

    private static String lastFour(String apiKey) {
        return apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
    }

    private static String maskFromHint(String hint) {
        return hint == null || hint.isBlank() ? "****" : "****" + hint;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record ResolvedModel(String baseUrl, String model, String apiKey) {
    }
}
