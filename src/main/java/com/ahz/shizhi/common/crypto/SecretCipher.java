package com.ahz.shizhi.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 加密用户自己上传的 API key。
 *
 * <p>明文 key 不入库，也不用可逆的「编码」冒充加密。主密钥来自环境变量，
 * 和数据库分开保管——只拿到数据库备份的人解不开这些 key。
 *
 * <p>没配主密钥时整个功能直接关闭，而不是退化成明文存储：配置缺失是部署问题，
 * 不该由数据安全来兜底。
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${shizhi.security.secret-key:}") String base64Key) {
        byte[] raw = base64Key == null || base64Key.isBlank()
                ? new byte[0]
                : Base64.getDecoder().decode(base64Key);
        this.key = raw.length == 32 ? new SecretKeySpec(raw, "AES") : null;
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireEnabled();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("加密 API key 失败", ex);
        }
    }

    public String decrypt(String packedBase64) {
        requireEnabled();
        try {
            byte[] packed = Base64.getDecoder().decode(packedBase64);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("解密 API key 失败，可能是主密钥换了", ex);
        }
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("未配置 shizhi.security.secret-key，无法读写用户密钥");
        }
    }
}
