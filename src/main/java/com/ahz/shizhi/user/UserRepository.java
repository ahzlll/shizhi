package com.ahz.shizhi.user;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("nickname"),
            rs.getInt("status"));

    private static final String SELECT_COLUMNS = "SELECT id, username, email, nickname, status FROM sys_user";

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    /** 登录时用：用户名或邮箱都能登录。 */
    public Optional<User> findByAccount(String account) {
        List<User> users = jdbc.query(
                SELECT_COLUMNS + " WHERE username = ? OR email = ?", ROW_MAPPER, account, account);
        return users.stream().findFirst();
    }

    public Optional<User> findById(long id) {
        List<User> users = jdbc.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id);
        return users.stream().findFirst();
    }

    /** 登录时用：用户名或邮箱都能登录，一并带出密码哈希，避免查两次。 */
    public Optional<Credential> findCredentialByAccount(String account) {
        List<Credential> rows = jdbc.query(
                "SELECT id, username, email, nickname, status, password_hash FROM sys_user"
                        + " WHERE username = ? OR email = ?",
                (rs, rowNum) -> new Credential(
                        new User(rs.getLong("id"), rs.getString("username"), rs.getString("email"),
                                rs.getString("nickname"), rs.getInt("status")),
                        rs.getString("password_hash")),
                account, account);
        return rows.stream().findFirst();
    }

    public long insert(String username, String email, String passwordHash) {
        Long id = jdbc.queryForObject(
                "INSERT INTO sys_user (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class, username, email, passwordHash);
        if (id == null) {
            throw new IllegalStateException("插入用户后没有返回主键");
        }
        return id;
    }

    public void touchLastLogin(long id) {
        jdbc.update("UPDATE sys_user SET last_login_at = now(), updated_at = now() WHERE id = ?", id);
    }

    /** 认证用的用户 + 密码哈希。哈希只在这一处出现，不落到 User 上。 */
    public record Credential(User user, String passwordHash) {
    }
}
