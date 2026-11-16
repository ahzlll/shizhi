package com.ahz.shizhi.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * 双阈值 flush 的纯逻辑。时间用注入的时钟拨快，不真的 sleep 两秒。
 */
class StreamBufferTest {

    private static final int MIN_CHARS = 40;
    private static final long MIN_INTERVAL_MILLIS = 2_000L;
    private static final long START = 1_000_000L;

    private final AtomicLong now = new AtomicLong(START);

    private StreamBuffer newBuffer() {
        return new StreamBuffer(MIN_CHARS, MIN_INTERVAL_MILLIS, now::get);
    }

    @Test
    void enoughCharsButTooSoonDoesNotFlush() {
        StreamBuffer buffer = newBuffer();

        buffer.append("字".repeat(100));
        now.set(START + MIN_INTERVAL_MILLIS - 1);

        assertThat(buffer.isDue()).isFalse();
    }

    @Test
    void longEnoughButTooFewCharsDoesNotFlush() {
        StreamBuffer buffer = newBuffer();

        buffer.append("字".repeat(MIN_CHARS - 1));
        now.set(START + 60_000L);

        assertThat(buffer.isDue()).isFalse();

        buffer.append("字");
        assertThat(buffer.isDue()).isTrue();
    }

    @Test
    void bothThresholdsMetFlushesExactlyWhenIntervalElapses() {
        StreamBuffer buffer = newBuffer();

        buffer.append("字".repeat(MIN_CHARS));
        now.set(START + MIN_INTERVAL_MILLIS - 1);
        assertThat(buffer.isDue()).isFalse();

        now.set(START + MIN_INTERVAL_MILLIS);
        assertThat(buffer.isDue()).isTrue();
        assertThat(buffer.pendingText()).hasSize(MIN_CHARS);
    }

    @Test
    void commitClearsBufferAndRestartsTheClock() {
        StreamBuffer buffer = newBuffer();
        buffer.append("字".repeat(50));
        now.set(START + 3_000L);

        assertThat(buffer.isDue()).isTrue();
        buffer.commit();

        assertThat(buffer.pendingText()).isEmpty();
        assertThat(buffer.pendingLength()).isZero();

        // 刚落库过，即便字够了也不能马上再写一次
        buffer.append("字".repeat(50));
        assertThat(buffer.isDue()).isFalse();

        now.set(START + 3_000L + MIN_INTERVAL_MILLIS);
        assertThat(buffer.isDue()).isTrue();
    }

    @Test
    void pendingTextDoesNotConsumeSoAFailedWriteCanBeRetried() {
        StreamBuffer buffer = newBuffer();
        buffer.append("字".repeat(50));
        now.set(START + MIN_INTERVAL_MILLIS);

        assertThat(buffer.pendingText()).hasSize(50);
        assertThat(buffer.pendingText()).hasSize(50);
        assertThat(buffer.pendingLength()).isEqualTo(50);
    }

    @Test
    void emptyTextIsIgnored() {
        StreamBuffer buffer = newBuffer();
        buffer.append(null);
        buffer.append("");
        now.set(START + 60_000L);

        assertThat(buffer.pendingLength()).isZero();
        assertThat(buffer.isDue()).isFalse();
    }
}
