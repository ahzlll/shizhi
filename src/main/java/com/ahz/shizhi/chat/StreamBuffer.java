package com.ahz.shizhi.chat;

import java.util.function.LongSupplier;

/**
 * 双阈值落库缓冲：**距上次落库 ≥ 2 秒 且 新增 ≥ 40 字符**才写一次。
 *
 * <p>只看时间，开头几秒会连写一串空 UPDATE；只看字符数，模型卡住时永远不落盘。
 * 两者都要满足，丢失的上界才是一个 flush 周期（05「消息生命周期与中断收敛」）。
 *
 * <p>时钟用 {@link LongSupplier} 注入而不是直接 {@code System.currentTimeMillis()}：
 * 这样单测能把时间"拨快"，不必真的 sleep 两秒。
 */
public final class StreamBuffer {

    private final int minChars;
    private final long minIntervalMillis;
    private final LongSupplier clock;
    private final StringBuilder pending = new StringBuilder();

    private long lastFlushAt;

    public StreamBuffer(int minChars, long minIntervalMillis, LongSupplier clock) {
        this.minChars = minChars;
        this.minIntervalMillis = minIntervalMillis;
        this.clock = clock;
        this.lastFlushAt = clock.getAsLong();
    }

    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        pending.append(text);
    }

    /** 两个阈值是否同时满足。 */
    public synchronized boolean isDue() {
        return pending.length() >= minChars
                && clock.getAsLong() - lastFlushAt >= minIntervalMillis;
    }

    /**
     * 取走全部待落库内容但不清空——调用方落库成功后再 {@link #commit()}。
     * 先取后清是为了让"落库失败"不至于把内容直接丢掉。
     */
    public synchronized String pendingText() {
        return pending.toString();
    }

    public synchronized int pendingLength() {
        return pending.length();
    }

    /** 落库成功后调用：清空缓冲并重置计时起点。 */
    public synchronized void commit() {
        pending.setLength(0);
        lastFlushAt = clock.getAsLong();
    }
}
