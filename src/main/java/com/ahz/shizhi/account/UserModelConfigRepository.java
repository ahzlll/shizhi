package com.ahz.shizhi.account;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserModelConfigRepository {

    private static final RowMapper<UserModelConfig> ROW_MAPPER = (rs, rowNum) -> new UserModelConfig(
            rs.getLong("user_id"),
            rs.getString("base_url"),
            rs.getString("model"),
            rs.getString("api_key_cipher"),
            rs.getString("api_key_hint"));

    private final JdbcTemplate jdbc;

    public UserModelConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserModelConfig> find(long userId) {
        return jdbc.query(
                        "SELECT user_id, base_url, model, api_key_cipher, api_key_hint"
                                + " FROM user_model_config WHERE user_id = ?",
                        ROW_MAPPER,
                        userId)
                .stream()
                .findFirst();
    }

    /**
     * 一人一条，所以直接 upsert。{@code created_at} 只在第一次写入时产生，
     * 重复保存不刷新它——它记的是「什么时候开始用自带模型」。
     */
    public void upsert(long userId, String baseUrl, String model, String apiKeyCipher, String apiKeyHint) {
        jdbc.update("""
                INSERT INTO user_model_config (user_id, base_url, model, api_key_cipher, api_key_hint)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET base_url = EXCLUDED.base_url,
                    model = EXCLUDED.model,
                    api_key_cipher = EXCLUDED.api_key_cipher,
                    api_key_hint = EXCLUDED.api_key_hint,
                    updated_at = now()
                """, userId, baseUrl, model, apiKeyCipher, apiKeyHint);
    }

    public int delete(long userId) {
        return jdbc.update("DELETE FROM user_model_config WHERE user_id = ?", userId);
    }
}
