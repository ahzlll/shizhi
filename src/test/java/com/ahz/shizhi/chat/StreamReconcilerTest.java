package com.ahz.shizhi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import com.ahz.shizhi.model.ModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 三层收敛里的前两层，以及启动时的超时参数校验。
 *
 * <p>直接调组件方法而不是等定时器：把 5 分钟的调度周期灌进一个单测只会让它变慢变脆，
 * 而「调度器到点会调用这个方法」是框架的事，不需要在这里证明。
 */
@SpringBootTest
@ActiveProfiles("test")
class StreamReconcilerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StreamReconciler reconciler;

    @Test
    void startupReconcileConvergesFreshGeneratingRowToo() {
        Fixture fixture = newSession();
        long justInserted = insertGenerating(fixture, 0L);

        reconciler.reconcileOnStartup();

        // 进程刚起来时本机不可能有活着的流，所以启动扫描不看时间：哪怕这行是刚插的
        assertThat(streamState(justInserted)).isEqualTo(2);
        assertThat(interruptReason(justInserted)).isEqualTo("SHUTDOWN");
    }

    @Test
    void scheduledReconcileOnlyTouchesRowsPastTheThreshold() {
        Fixture fixture = newSession();
        long longStalled = insertGenerating(fixture, 30 * 60L);
        long recentlyActive = insertGenerating(fixture, 60L);

        reconciler.reconcileStale();

        assertThat(streamState(longStalled)).isEqualTo(2);
        assertThat(interruptReason(longStalled)).isEqualTo("SHUTDOWN");
        // 一分钟前还在吐字，不能被当成僵尸
        assertThat(streamState(recentlyActive)).isZero();
        assertThat(interruptReason(recentlyActive)).isNull();
    }

    @Test
    void refusesToStartWhenTotalTimeoutLeavesNoMargin() {
        ModelProperties tooSlow = new ModelProperties("stub", null, null, null,
                null, null, Duration.ofSeconds(300));

        assertThatThrownBy(new ChatConfig(tooSlow)::verifyReconcileThreshold)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shizhi.model.total-timeout")
                .hasMessageContaining("STALE_AFTER_SECONDS");
    }

    @Test
    void acceptsTheDefaultTimeoutRelation() {
        ModelProperties defaults = new ModelProperties(null, null, null, null, null, null, null);

        assertThatCode(new ChatConfig(defaults)::verifyReconcileThreshold).doesNotThrowAnyException();
    }

    private record Fixture(long userId, long sessionId) {
    }

    private Fixture newSession() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long userId = jdbc.queryForObject(
                "INSERT INTO sys_user (username, email, password_hash) VALUES (?, ?, 'x') RETURNING id",
                Long.class, "rc" + suffix, "rc" + suffix + "@example.com");
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO chat_session (user_id) VALUES (?) RETURNING id", Long.class, userId);
        assertThat(userId).isNotNull();
        assertThat(sessionId).isNotNull();
        return new Fixture(userId, sessionId);
    }

    private long insertGenerating(Fixture fixture, long ageSeconds) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, created_at, updated_at)
                VALUES (?, ?, 'assistant', '', 0,
                        now() - (? * interval '1 second'), now() - (? * interval '1 second'))
                RETURNING id
                """, Long.class, fixture.sessionId(), fixture.userId(), ageSeconds, ageSeconds);
        assertThat(id).isNotNull();
        return id;
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
