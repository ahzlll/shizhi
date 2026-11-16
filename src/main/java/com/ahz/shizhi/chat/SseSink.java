package com.ahz.shizhi.chat;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link SseEmitter} 的线程安全外壳。
 *
 * <p>SseEmitter 不是线程安全的，而这条链路上同时有四个写者：业务线程推 ack/delta/done/error、
 * 调度线程推 heartbeat、容器线程回调 onError/onCompletion。所有发送都过同一把锁，
 * 事件之间的顺序才不会被并发写搅乱。
 *
 * <p>客户端断开后 {@code send} 会抛 IOException。这里吞掉并置中断标记，返回 false——
 * IOException 冒到线程池只会变成一行没人看的堆栈，而"false"是上层能据以停下的信号。
 */
final class SseSink {

    private static final Logger log = LoggerFactory.getLogger(SseSink.class);

    private final SseEmitter emitter;
    private final Object lock = new Object();

    private volatile boolean closed;

    SseSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    boolean isClosed() {
        return closed;
    }

    void markClosed() {
        closed = true;
    }

    /**
     * @return true 表示已送出；false 表示连接已断或 emitter 已完成，调用方应当停止生成
     */
    boolean send(String eventName, Object data) {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException ex) {
                // 客户端断开（IOException）或 emitter 已结束（IllegalStateException）都只说明"没人收了"
                log.info("SSE 连接已断开，停止推送 event={}", eventName);
                closed = true;
                return false;
            }
        }
    }

    void complete() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                log.debug("关闭 SSE 连接时出现异常", ex);
            }
        }
    }
}
