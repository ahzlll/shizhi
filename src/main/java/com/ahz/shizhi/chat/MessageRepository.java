package com.ahz.shizhi.chat;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ahz.shizhi.chat.dto.MessageView;
import com.ahz.shizhi.model.Turn;
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
     * 收敛阈值必须大于模型总超时（{@code shizhi.model.total-timeout}，当前 180s）。
     * 取小了会发生什么：一个正常生成、只是吐字慢的流在 120s 被读时收敛判成「已中断」，
     * 之后它跑完又写回 {@code stream_state=1}，用户看到的状态翻转一次、且没人解释得清。
     * 05 里「模型超时 × 2」是量级意思（它举例时模型超时是 60s），核心是留够余量。
     */
    private static final int STALE_AFTER_SECONDS = 210;

    private static final String RECONCILE_ZOMBIES =
            "UPDATE chat_message SET stream_state = 2, interrupt_reason = 'SHUTDOWN', updated_at = now()"
                    + " WHERE session_id = ? AND stream_state = 0"
                    + " AND updated_at < now() - (? * interval '1 second')";

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
        return jdbc.update(RECONCILE_ZOMBIES, sessionId, STALE_AFTER_SECONDS);
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

    /**
     * 插一行消息。用户消息直接是终态（{@code stream_state=1}），assistant 那一行是占位行
     * （{@code stream_state=0}、{@code content=''}）——按 03 §3.9 第 1 条，占位行必须在流开始前写，
     * 否则进程一崩，内存里的半截回答连个落点都没有。
     */
    public long insertMessage(long sessionId, long userId, String role, String content, int streamState) {
        Long id = jdbc.queryForObject(
                "INSERT INTO chat_message (session_id, user_id, role, content, stream_state, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, now()) RETURNING id",
                Long.class, sessionId, userId, role, content, streamState);
        if (id == null) {
            throw new IllegalStateException("插入消息后没有返回主键");
        }
        return id;
    }

    /**
     * 一轮问答对应两行消息，所以 +2；同时刷新 {@code last_active_at} 让会话浮到列表最前。
     *
     * <p>{@code COALESCE(title, ?)} 是"首问定标题"的实现：03 §3.3 的口径是标题取第一个问题前 30 字，
     * 建会话时还没有问题。用 COALESCE 而不是判断再写，省掉一次读，也不会被并发写覆盖。
     */
    public void bumpSessionAfterTurn(long sessionId, String titleFromFirstQuestion) {
        jdbc.update("UPDATE chat_session SET message_count = message_count + 2,"
                        + " last_active_at = now(), title = COALESCE(title, ?) WHERE id = ?",
                titleFromFirstQuestion, sessionId);
    }

    /**
     * 周期 flush。<b>必须带 {@code stream_state = 0} 条件</b>：读时收敛会把僵尸行改成 2，
     * 无条件更新又会把它写回"生成中"，永久转圈就回来了。
     *
     * @return 影响行数。0 表示这行已经不是"生成中"了（被收敛或已完成），调用方应当停下
     */
    public int appendContent(long messageId, String delta) {
        if (delta == null || delta.isEmpty()) {
            return 0;
        }
        return jdbc.update(
                "UPDATE chat_message SET content = content || ?, updated_at = now()"
                        + " WHERE id = ? AND stream_state = 0",
                delta, messageId);
    }

    /**
     * 正常结束。<b>这是整条链路的提交点，必须是所有写库动作里的最后一个</b>——
     * 顺序反了，中间崩掉就是"消息标记完成、内容其实是半截"。
     *
     * <p>末尾内容一并写入（{@code content || ?}），省掉一次"先 flush 再标完成"的两次写。
     */
    public int markCompleted(long messageId, String tail, int promptTokens, int completionTokens) {
        return jdbc.update("UPDATE chat_message SET content = content || ?, stream_state = 1,"
                        + " prompt_tokens = ?, completion_tokens = ?, updated_at = now() WHERE id = ?",
                tail == null ? "" : tail, promptTokens, completionTokens, messageId);
    }

    /**
     * 中途停下（客户端断开 / 上游异常 / 超时）。
     *
     * @param tail 补写的内容；客户端断开时传 null —— 04 §4.4 的口径是"已 flush 的保留，
     *             未 flush 的丢失（上界是一个 flush 周期）"，所以此刻缓冲区里的东西不再落库
     */
    public int markInterrupted(long messageId, String tail, String reason) {
        if (tail == null) {
            return jdbc.update("UPDATE chat_message SET stream_state = 2, interrupt_reason = ?,"
                    + " updated_at = now() WHERE id = ?", reason, messageId);
        }
        return jdbc.update("UPDATE chat_message SET content = content || ?, stream_state = 2,"
                        + " interrupt_reason = ?, updated_at = now() WHERE id = ?",
                tail, reason, messageId);
    }

    /** 一个字都没出来就失败。前端不渲染气泡，只提示错误（03 §3.9 的四态表）。 */
    public int markFailed(long messageId, String reason) {
        return jdbc.update("UPDATE chat_message SET stream_state = 3, interrupt_reason = ?,"
                + " updated_at = now() WHERE id = ?", reason, messageId);
    }

    /**
     * 取提示词要用的最近若干条消息，按时间正序返回。
     *
     * <p>三个过滤条件各有原因：{@code superseded_by IS NULL} 排除被重试替代的回答；
     * {@code stream_state <> 0} 排除正在生成的占位行（它此刻还是空的）；
     * {@code content <> ''} 排除 model 返回空串的那类行——空 turn 上游直接拒收。
     */
    public List<Turn> recentTurns(long sessionId, int limit) {
        List<Turn> descending = jdbc.query(
                "SELECT role, content FROM chat_message WHERE session_id = ?"
                        + " AND superseded_by IS NULL AND stream_state <> 0 AND content <> ''"
                        + " ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new Turn(rs.getString("role"), rs.getString("content")),
                sessionId, limit);
        List<Turn> ascending = new ArrayList<>(descending);
        Collections.reverse(ascending);
        return ascending;
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
