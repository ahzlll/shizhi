package com.ahz.shizhi.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.ahz.shizhi.chat.dto.SessionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 会话的读写。查询结果直接是给前端的视图——字段和 {@link SessionView} 一一对应，
 * 中间再套一层带行为的实体没有收益。
 */
@Repository
public class SessionRepository {

    private static final RowMapper<SessionView> ROW_MAPPER = (rs, rowNum) -> new SessionView(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("skill_code"),
            rs.getInt("message_count"),
            rs.getObject("last_active_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getBoolean("pinned"),
            rs.getObject("deleted_at", OffsetDateTime.class));

    private static final String SELECT_COLUMNS =
            "SELECT id, title, skill_code, message_count, last_active_at, created_at, pinned, deleted_at"
                    + " FROM chat_session";

    /**
     * 建会话时就把 {@code last_active_at} 写成 now()：会话列表按它倒序排列，
     * 新建的会话必须立刻出现在最上面，否则用户建完就找不到了。
     *
     * <p>{@code title} 故意不写：标题取第一个问题的前 30 个字，此刻还没有问题。
     */
    private static final String INSERT_SESSION =
            "INSERT INTO chat_session (user_id, skill_code, last_active_at) VALUES (?, ?, now())"
                    + " RETURNING id, title, skill_code, message_count, last_active_at, created_at, pinned, deleted_at";

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SessionView insert(long userId, String skillCode) {
        SessionView created = jdbc.queryForObject(INSERT_SESSION, ROW_MAPPER, userId, skillCode);
        if (created == null) {
            throw new IllegalStateException("插入会话后没有返回记录");
        }
        return created;
    }

    public long countByUser(long userId) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM chat_session WHERE user_id = ? AND deleted_at IS NULL",
                Long.class, userId);
        return total == null ? 0L : total;
    }

    /** 排序固定为 {@code last_active_at DESC, id DESC}：同一毫秒建的两个会话靠 id 保证顺序稳定。 */
    public List<SessionView> pageByUser(long userId, int limit, long offset) {
        return jdbc.query(
                SELECT_COLUMNS + " WHERE user_id = ? AND deleted_at IS NULL"
                        + " ORDER BY pinned DESC, last_active_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, userId, limit, offset);
    }

    /** 改名。返回受影响行数，调用方据此区分「没这条会话」和「不是你的」。 */
    public int updateTitle(long sessionId, long userId, String title) {
        return jdbc.update("UPDATE chat_session SET title = ? WHERE id = ? AND user_id = ?",
                title, sessionId, userId);
    }

    /** 回收站里的会话不算「你的活跃会话」，置顶、改名、发消息都不该再作用到它。 */
    public List<SessionView> findById(long sessionId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE id = ? AND deleted_at IS NULL", ROW_MAPPER, sessionId);
    }

    /** 软删除：只打时间戳，消息原样留着，30 天内还能恢复。 */
    public int deleteById(long sessionId, long userId) {
        return jdbc.update("UPDATE chat_session SET deleted_at = now()"
                + " WHERE id = ? AND user_id = ? AND deleted_at IS NULL", sessionId, userId);
    }

    public List<SessionView> findDeleted(long userId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE user_id = ? AND deleted_at IS NOT NULL"
                + " ORDER BY deleted_at DESC", ROW_MAPPER, userId);
    }

    public int restore(long sessionId, long userId) {
        return jdbc.update("UPDATE chat_session SET deleted_at = NULL"
                + " WHERE id = ? AND user_id = ? AND deleted_at IS NOT NULL", sessionId, userId);
    }

    /** 彻底删一条：消息先删，会话后删，返回会话受影响行数用于判断是否存在。 */
    public int purge(long sessionId, long userId) {
        jdbc.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
        return jdbc.update("DELETE FROM chat_session WHERE id = ? AND user_id = ? AND deleted_at IS NOT NULL",
                sessionId, userId);
    }

    /** 批量软删。没选中的会话不受影响，返回真正被删的行数。 */
    public int softDeleteBatch(long userId, List<Long> ids) {
        return jdbc.update("UPDATE chat_session SET deleted_at = now()"
                        + " WHERE user_id = ? AND deleted_at IS NULL AND id IN (" + placeholders(ids.size()) + ")",
                args(userId, ids));
    }

    public int restoreBatch(long userId, List<Long> ids) {
        return jdbc.update("UPDATE chat_session SET deleted_at = NULL"
                        + " WHERE user_id = ? AND deleted_at IS NOT NULL AND id IN (" + placeholders(ids.size()) + ")",
                args(userId, ids));
    }

    /** 批量彻底删除：先删消息再删会话，两步都限定在回收站里。 */
    public int purgeBatch(long userId, List<Long> ids) {
        String in = placeholders(ids.size());
        jdbc.update("DELETE FROM chat_message WHERE session_id IN ("
                        + " SELECT id FROM chat_session WHERE user_id = ? AND deleted_at IS NOT NULL"
                        + " AND id IN (" + in + "))",
                args(userId, ids));
        return jdbc.update("DELETE FROM chat_session"
                        + " WHERE user_id = ? AND deleted_at IS NOT NULL AND id IN (" + in + ")",
                args(userId, ids));
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static Object[] args(long userId, List<Long> ids) {
        Object[] values = new Object[ids.size() + 1];
        values[0] = userId;
        for (int i = 0; i < ids.size(); i++) {
            values[i + 1] = ids.get(i);
        }
        return values;
    }

    /** 清理超过保留期的回收站会话，返回删掉的会话数。 */
    public int purgeExpired(int retentionDays) {
        jdbc.update("DELETE FROM chat_message WHERE session_id IN (SELECT id FROM chat_session"
                + " WHERE deleted_at < now() - make_interval(days => ?))", retentionDays);
        return jdbc.update("DELETE FROM chat_session"
                + " WHERE deleted_at < now() - make_interval(days => ?)", retentionDays);
    }

    /** 搜索：标题命中，或者任意一条消息正文命中。消息多时靠 like 也够用，后续要精确再上全文索引。 */
    public List<SessionView> search(long userId, String keyword, int limit) {
        String pattern = "%" + keyword + "%";
        return jdbc.query(
                SELECT_COLUMNS + " WHERE user_id = ? AND (title ILIKE ? OR EXISTS ("
                        + " SELECT 1 FROM chat_message m WHERE m.session_id = chat_session.id"
                        + " AND m.content ILIKE ?))"
                        + " ORDER BY pinned DESC, last_active_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, userId, pattern, pattern, limit);
    }

    /** 置顶只改标记，不动 last_active_at——置顶不该把会话的活跃时间刷新成现在。 */
    public int updatePinned(long sessionId, long userId, boolean pinned) {
        return jdbc.update("UPDATE chat_session SET pinned = ? WHERE id = ? AND user_id = ?",
                pinned, sessionId, userId);
    }

    /**
     * 只取归属用户，用于「会话不存在」与「不是你的会话」两种失败的区分。
     * 合并成一个查询就意味着前端拿不到准确的失败原因。
     */
    public Optional<Long> findOwnerId(long sessionId) {
        List<Long> owners = jdbc.query(
                "SELECT user_id FROM chat_session WHERE id = ?",
                (rs, rowNum) -> rs.getLong("user_id"),
                sessionId);
        return owners.stream().findFirst();
    }
}
