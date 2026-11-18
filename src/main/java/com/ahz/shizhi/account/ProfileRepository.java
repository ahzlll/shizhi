package com.ahz.shizhi.account;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.ahz.shizhi.account.dto.ProfileView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {

    public record Avatar(byte[] data, String contentType) {
    }

    private static final RowMapper<ProfileView> ROW_MAPPER = (rs, rowNum) -> new ProfileView(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("nickname"),
            rs.getObject("avatar_updated_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProfileView> find(long userId) {
        return jdbc.query(
                        "SELECT id, username, email, nickname, avatar_updated_at, created_at"
                                + " FROM sys_user WHERE id = ?",
                        ROW_MAPPER,
                        userId)
                .stream()
                .findFirst();
    }

    public int updateNickname(long userId, String nickname) {
        return jdbc.update("UPDATE sys_user SET nickname = ?, updated_at = now() WHERE id = ?",
                nickname, userId);
    }

    public int saveAvatar(long userId, byte[] data, String contentType) {
        return jdbc.update(
                "UPDATE sys_user SET avatar_data = ?, avatar_type = ?, avatar_updated_at = now()"
                        + " WHERE id = ?",
                data, contentType, userId);
    }

    public Optional<Avatar> findAvatar(long userId) {
        return jdbc.query(
                        "SELECT avatar_data, avatar_type FROM sys_user"
                                + " WHERE id = ? AND avatar_data IS NOT NULL",
                        (rs, rowNum) -> new Avatar(rs.getBytes("avatar_data"), rs.getString("avatar_type")),
                        userId)
                .stream()
                .findFirst();
    }

    public int clearAvatar(long userId) {
        return jdbc.update(
                "UPDATE sys_user SET avatar_data = NULL, avatar_type = NULL, avatar_updated_at = NULL"
                        + " WHERE id = ?",
                userId);
    }
}
