package com.ahz.shizhi.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(filled(1));
    private static final String KEY_B = Base64.getEncoder().encodeToString(filled(2));

    @Test
    void encryptsAndDecrypts() {
        SecretCipher cipher = new SecretCipher(KEY_A);

        String encrypted = cipher.encrypt("sk-1234567890");

        assertThat(encrypted).isNotEqualTo("sk-1234567890");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-1234567890");
    }

    @Test
    void producesDifferentCipherTextForSameInput() {
        SecretCipher cipher = new SecretCipher(KEY_A);

        // 每次用新的 IV，同一把 key 存两遍不会出现相同的密文
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void cannotDecryptWithAnotherKey() {
        String encrypted = new SecretCipher(KEY_A).encrypt("sk-abc");

        assertThatThrownBy(() -> new SecretCipher(KEY_B).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledWhenKeyMissing() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.enabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("sk-abc")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledWhenKeyIsNot32Bytes() {
        SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(filled(2)));

        assertThat(cipher.enabled()).isFalse();
    }

    private static byte[] filled(int value) {
        byte[] raw = new byte[value == 1 ? 32 : 16];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) value;
        }
        return raw;
    }
}
