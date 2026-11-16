package com.ahz.shizhi.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 证明定时扫描真的被调度器跑起来了。
 *
 * <p>直接调 {@code reconcileStale()} 只能证明那段 SQL 对；把 {@code @Scheduled} 注解删掉、
 * 或者漏了 {@code @EnableScheduling}，那种测试照样绿。这里一行代码都不主动调用，
 * 只把扫描间隔压到 100 毫秒然后等数据库自己变。
 *
 * <p>间隔是 100 毫秒，会连带扫这个上下文里的所有数据，所以必须独立于其他测试类的上下文。
 */
@SpringBootTest(properties = "shizhi.chat.reconcile-interval=100ms")
@ActiveProfiles("test")
class StreamReconcilerScheduleTest {

    private static final long AWAIT_TIMEOUT_MILLIS = 3_000L;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void scheduledScanConvergesStaleRowWithoutBeingCalled() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long userId = jdbc.queryForObject(
                "INSERT INTO sys_user (username, email, password_hash) VALUES (?, ?, 'x') RETURNING id",
                Long.class, "sc" + suffix, "sc" + suffix + "@example.com");
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO chat_session (user_id) VALUES (?) RETURNING id", Long.class, userId);
        Long messageId = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, 'assistant', '', 0,
                        now() - interval '30 minutes', now() - interval '30 minutes')
                RETURNING id
                """, Long.class, sessionId, userId);
        assertThat(messageId).isNotNull();

        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        int state = 0;
        String reason = null;
        while (System.currentTimeMillis() < deadline) {
            state = streamState(messageId);
            reason = interruptReason(messageId);
            if (state == 2 && "SHUTDOWN".equals(reason)) {
                break;
            }
            Thread.sleep(50L);
        }

        assertThat(state).as("定时扫描必须在 3 秒内把这行收敛掉，否则说明调度器没跑").isEqualTo(2);
        assertThat(reason).isEqualTo("SHUTDOWN");
    }

    private int streamState(long messageId) {
        Integer state = jdbc.queryForObject(
                "SELECT stream_state FROM chat_message WHERE id = ?", Integer.class, messageId);
        return state == null ? -1 : state;
    }

    private String interruptReason(long messageId) {
        return jdbc.queryForObject(
                "SELECT interrupt_reason FROM chat_message WHERE id = ?", String.class, messageId);
    }
}
