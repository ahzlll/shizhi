package com.ahz.shizhi.account;

/**
 * 用户自带的模型配置。{@code apiKeyCipher} 是密文，明文只在加密和解密的那一瞬间存在。
 */
public record UserModelConfig(long userId, String baseUrl, String model,
                              String apiKeyCipher, String apiKeyHint) {
}
