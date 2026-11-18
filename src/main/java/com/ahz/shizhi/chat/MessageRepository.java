package com.ahz.shizhi.chat;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.ahz.shizhi.chat.dto.MessageView;
import com.ahz.shizhi.model.Turn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 消息的读写。
 *
 * <p>拉历史时顺手把挂住的「生成中」改成「已中断」：进程崩了或者流挂死时，没有代码能去改状态，
 * 只能等用户来拉历史这一下顺手修掉，否则那个气泡会一直转。
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
     */
    static final int STALE_AFTER_SECONDS = 210;

    /** 消息状态 3：一个字都没生成出来。历史查询据此把这类行排除掉。 */
    private static final int STREAM_FAILED = 3;

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
     * 启动时的全表收敛，<b>不加时间条件</b>：进程刚起来，本机不可能还有活着的流。
     * 前提是单实例部署——多实例时会误杀另一台正在生成的回答。
     */
    public int reconcileAllGenerating() {
        return jdbc.update("UPDATE chat_message SET stream_state = 2, interrupt_reason = 'SHUTDOWN',"
                + " updated_at = now() WHERE stream_state = 0");
    }

    /**
     * 定时兜底：进程活着但流挂死（上游 TCP 半开、读超时没配）时只有这一层能发现。
     * 只碰超过阈值的行——阈值远大于一个 flush 周期，正在吐字的流不会被误杀。
     */
    public int reconcileStale() {
        return jdbc.update("UPDATE chat_message SET stream_state = 2, interrupt_reason = 'SHUTDOWN',"
                        + " updated_at = now() WHERE stream_state = 0"
                        + " AND updated_at < now() - (? * interval '1 second')",
                STALE_AFTER_SECONDS);
    }

    /**
     * 按 id 倒序取一页，调用方负责反转成时间正序。
     *
     * <p>倒序取是为了「取最近的 N 条」——游标分页永远从最新往回翻，正序取要先把整段读出来再截断。
     * {@code superseded_by IS NULL} 过滤掉被重试替代的行，否则历史里会出现两三条几乎一样的回答。
     *
     * <p>{@code stream_state <> 3} 过滤掉「失败且一个字都没产出」的行。它们记录的是「这次提问没答出来」，
     * 但前端本来就不渲染空气泡；留在返回里只会随重试次数累积——每失败一次多一条，
     * 历史会越长越脏。所以在查询层直接挡掉，而不是把判断推给调用方。
     * 初始提问那条用户消息仍在，所以「我问过这句话」这个事实不会丢。
     *
     * @param cursor 取比它更早的消息，null 表示从最新的开始
     * @param limit  期望条数 + 1，多取的那条只用来判断还有没有下一页
     */
    public List<MessageView> findBySession(long sessionId, Long cursor, int limit) {
        boolean hasCursor = cursor != null;
        String sql = SELECT_COLUMNS
                + " WHERE session_id = ?"
                + (hasCursor ? " AND id < ?" : "")
                + " AND superseded_by IS NULL AND stream_state <> ? ORDER BY id DESC LIMIT ?";
        if (hasCursor) {
            return jdbc.query(sql, ROW_MAPPER, sessionId, cursor, STREAM_FAILED, limit);
        }
        return jdbc.query(sql, ROW_MAPPER, sessionId, STREAM_FAILED, limit);
    }

    public int deleteBySession(long sessionId) {
        return jdbc.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
    }

    /** 判断是不是第一轮：标题只在第一轮结束时生成一次。 */
    public int countUserMessages(long sessionId) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE session_id = ? AND role = 'user'",
                Integer.class, sessionId);
        return total == null ? 0 : total;
    }

    /**
     * 插一行消息。用户消息直接是终态（{@code stream_state=1}），assistant 那一行是占位行
     * （{@code stream_state=0}、{@code content=''}）。占位行必须在流开始前写，
     * 否则进程一崩，内存里的半截回答就没了。
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
     * <p>{@code COALESCE(title, ?)} 是"首问定标题"的实现：标题取第一个问题的前 30 个字，
     * 而建会话的时候还没有问题。用 COALESCE 而不是先判断再写，省掉一次读，也不会被并发写覆盖。
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
     * @param tail 补写的内容；客户端断开时传 null：已经落库的保留，
     *             没来得及写的就丢了，最多丢一个 flush 周期，所以此刻缓冲区里的东西不再落库
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

    /** 一个字都没出来就失败。前端不渲染气泡，只提示错误。 */
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

    /**
     * 重试一条回答需要知道的旧行信息。一次查出来比查四次省三次往返，
     * 也让「同一条消息被两个请求同时重试」这件事只有一个读点。
     */
    public record RetrySource(
            long id,
            long userId,
            long sessionId,
            String role,
            int streamState,
            Long supersededBy,
            List<Long> refNoteIds,
            List<Long> refConceptIds
    ) {
    }

    public Optional<RetrySource> findRetrySource(long messageId) {
        List<RetrySource> rows = jdbc.query(
                "SELECT id, user_id, session_id, role, stream_state, superseded_by,"
                        + " ref_note_ids, ref_concept_ids FROM chat_message WHERE id = ?",
                (rs, rowNum) -> new RetrySource(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getLong("session_id"),
                        rs.getString("role"),
                        rs.getInt("stream_state"),
                        rs.getObject("superseded_by", Long.class),
                        readIdArray(rs, "ref_note_ids"),
                        readIdArray(rs, "ref_concept_ids")),
                messageId);
        return rows.stream().findFirst();
    }

    /**
     * 顺 {@code superseded_by} 往回数几代：原回答算 1、重试一次算 2。
     * 用递归 CTE 一次算完，避免在 Java 里一层层查库。
     */
    public long chainDepth(long messageId) {
        Long depth = jdbc.queryForObject(
                "WITH RECURSIVE chain AS ("
                        + " SELECT id FROM chat_message WHERE id = ?"
                        + " UNION ALL"
                        + " SELECT m.id FROM chat_message m JOIN chain c ON m.superseded_by = c.id"
                        + ") SELECT count(*) FROM chain",
                Long.class, messageId);
        return depth == null ? 0L : depth;
    }

    /**
     * 插重试出来的新行。{@code ref_note_ids} / {@code ref_concept_ids} 从旧行复制过来：
     * 重试不重新检索，否则两次回答引用的笔记可能不一样，用户会以为系统在乱说。
     */
    public long insertRetryPlaceholder(long sessionId, long userId,
                                       List<Long> refNoteIds, List<Long> refConceptIds) {
        Long id = jdbc.queryForObject(
                "INSERT INTO chat_message (session_id, user_id, role, content, stream_state, updated_at,"
                        + " ref_note_ids, ref_concept_ids)"
                        + " VALUES (?, ?, 'assistant', '', 0, now(), ?::bigint[], ?::bigint[]) RETURNING id",
                Long.class, sessionId, userId, pgArray(refNoteIds), pgArray(refConceptIds));
        if (id == null) {
            throw new IllegalStateException("插入重试消息后没有返回主键");
        }
        return id;
    }

    /**
     * 把旧行标记为已被新行替代。
     *
     * @return 影响行数。0 表示别的请求已经抢先标记过——调用方据此回滚，不会留下两条新行
     */
    public int markSuperseded(long messageId, long supersededBy) {
        return jdbc.update("UPDATE chat_message SET superseded_by = ?, updated_at = now()"
                + " WHERE id = ? AND superseded_by IS NULL", supersededBy, messageId);
    }

    /**
     * 撤回「已被替代」。条件里带 {@code superseded_by = ?} 是为了只撤自己写的那条：
     * 并发下这条链可能已经被别人接到更后面，无脑清空会把别人的重试关系弄坏。
     */
    public int clearSuperseded(long messageId, long expectedSupersededBy) {
        return jdbc.update("UPDATE chat_message SET superseded_by = NULL WHERE id = ? AND superseded_by = ?",
                messageId, expectedSupersededBy);
    }

    /** 重试只多一行 assistant，所以 +1；标题保持首次提问时的那条。 */
    public void bumpSessionAfterRetry(long sessionId) {
        jdbc.update("UPDATE chat_session SET message_count = message_count + 1, last_active_at = now()"
                + " WHERE id = ?", sessionId);
    }

    /** 原回答之前最近的一条用户提问。前端靠它把重试出来的气泡和对应的提问对上。 */
    public long previousUserMessageId(long sessionId, long beforeMessageId) {
        Long id = jdbc.queryForObject(
                "SELECT COALESCE(max(id), 0) FROM chat_message"
                        + " WHERE session_id = ? AND role = 'user' AND id < ?",
                Long.class, sessionId, beforeMessageId);
        return id == null ? 0L : id;
    }

    /** pgjdbc 不认 {@code List<Long>}，拼成 {@code {1,2}} 再交给 SQL 里的 {@code ::bigint[]}。 */
    private static String pgArray(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(ids.get(i));
        }
        return text.append('}').toString();
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
