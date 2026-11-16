package com.ahz.shizhi.chat;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.ahz.shizhi.chat.dto.MessageView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 历史消息的读取。
 *
 * <p>这里同时是 docs/03 §3.9 第 3 条「读时惰性收敛」的落点：用户拉历史时顺手把僵尸行改掉，
 * 保证没有人会看到一个永久转圈的泡泡。
 */
@Repository
public class MessageRepository {

    private static final RowMapper<MessageView> ROW_MAPPER = (rs, rowNum) -> new MessageView(
            rs.getLong("id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getInt("stream_state"),
            rs.getString("interrupt_reason"),
            rs.getObject("superseded_by", Long.class),
            readIdArray(rs, "ref_note_ids"),
            readIdArray(rs, "ref_concept_ids"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String SELECT_COLUMNS =
            "SELECT id, role, content, stream_state, interrupt_reason, superseded_by,"
                    + " ref_note_ids, ref_concept_ids, created_at FROM chat_message";

    /**
     * 阈值定 10 分钟：一个 flush 周期是 2 秒，差三个数量级，正在生成的流不会被误判成僵尸；
     * 反过来又远短于用户能忍的等待，刷新一次就能看到「已中断」而不是继续转圈。
     */
    private static final String RECONCILE_ZOMBIES =
            "UPDATE chat_message SET stream_state = 2, interrupt_reason = 'SHUTDOWN', updated_at = now()"
                    + " WHERE session_id = ? AND stream_state = 0"
                    + " AND updated_at < now() - interval '10 minutes'";

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 把超时仍停在「生成中」的行收敛为「中断 / SHUTDOWN」。
     *
     * @return 被改掉的行数，仅用于日志
     */
    public int reconcileZombies(long sessionId) {
        return jdbc.update(RECONCILE_ZOMBIES, sessionId);
    }

    /**
     * 按 id 倒序取一页，调用方负责反转成时间正序。
     *
     * <p>倒序取是为了「取最近的 N 条」——游标分页永远从最新往回翻，正序取要先把整段读出来再截断。
     * {@code superseded_by IS NULL} 过滤掉被重试替代的行，否则历史里会出现两三条几乎一样的回答。
     *
     * @param cursor 取比它更早的消息，null 表示从最新的开始
     * @param limit  期望条数 + 1，多取的那条只用来判断还有没有下一页
     */
    public List<MessageView> findBySession(long sessionId, Long cursor, int limit) {
        boolean hasCursor = cursor != null;
        String sql = SELECT_COLUMNS
                + " WHERE session_id = ?"
                + (hasCursor ? " AND id < ?" : "")
                + " AND superseded_by IS NULL ORDER BY id DESC LIMIT ?";
        if (hasCursor) {
            return jdbc.query(sql, ROW_MAPPER, sessionId, cursor, limit);
        }
        return jdbc.query(sql, ROW_MAPPER, sessionId, limit);
    }

    /** {@code bigint[]} 为空时 pgjdbc 给 null，这里统一成空列表，前端不用写空值判断。 */
    private static List<Long> readIdArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw == null) {
                return List.of();
            }
            Object[] values = (Object[]) raw;
            List<Long> ids = new ArrayList<>(values.length);
            for (Object value : values) {
                if (value instanceof Number number) {
                    ids.add(number.longValue());
                }
            }
            return List.copyOf(ids);
        } finally {
            array.free();
        }
    }
}
