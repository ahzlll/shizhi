package com.ahz.shizhi.chat;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.ahz.shizhi.model.ChatModelClient;
import com.ahz.shizhi.model.ModelResult;
import com.ahz.shizhi.model.Turn;

/**
 * 可编排的假模型：测试用 {@code play(...)} 指定这一轮吐什么、什么时候失败。
 *
 * <p>为什么需要它而不是直接用 {@link com.ahz.shizhi.model.StubChatClient}：
 * 假模型只会"正常说完"，而这张表里的四行——正常结束、中途出错、一个字没出就失败、
 * 超时——恰恰是这次最需要被证明的东西。
 */
public class ScriptedChatModelClient implements ChatModelClient {

    /**
     * @param deltas               依次回调的增量片段
     * @param failure              吐完之后抛出的异常，null 表示正常结束
     * @param delayBeforeFirstMillis 第一次回调之前的等待，用来造出心跳/超时的窗口
     * @param delayPerDeltaMillis  每次回调之间的间隔，用来让客户端有机会断开
     */
    public record Script(
            List<String> deltas,
            RuntimeException failure,
            long delayBeforeFirstMillis,
            long delayPerDeltaMillis
    ) {

        public static Script deltas(String... deltas) {
            return new Script(List.of(deltas), null, 0L, 0L);
        }

        public static Script deltasThenFail(RuntimeException failure, String... deltas) {
            return new Script(List.of(deltas), failure, 0L, 0L);
        }

        public static Script slowDeltas(long delayPerDeltaMillis, String... deltas) {
            return new Script(List.of(deltas), null, 0L, delayPerDeltaMillis);
        }

        public static Script failAfterDelay(RuntimeException failure, long delayMillis) {
            return new Script(List.of(), failure, delayMillis, 0L);
        }

        public static Script firstDeltaAfter(long delayMillis, String... deltas) {
            return new Script(List.of(deltas), null, delayMillis, 0L);
        }
    }

    private final AtomicInteger calls = new AtomicInteger();
    private final ConcurrentLinkedQueue<Script> queued = new ConcurrentLinkedQueue<>();
    private volatile Script script = Script.deltas("默认回答");
    private volatile List<Turn> lastTurns = List.of();

    public void play(Script next) {
        this.script = next;
        this.queued.clear();
        this.calls.set(0);
    }

    /**
     * 并发用例专用：按调用到达顺序取剧本。{@link #play} 是单槽（后写覆盖前写），
     * 两个线程同时发消息时没法分别指定台词，只能排队分发。
     */
    public void enqueue(Script next) {
        this.queued.add(next);
    }

    public int calls() {
        return calls.get();
    }

    /** 最近一次调用收到的提示词，用来断言提示词确实带上了 system 身份和历史。 */
    public List<Turn> lastTurns() {
        return lastTurns;
    }

    @Override
    public ModelResult stream(List<Turn> turns, Consumer<String> onDelta) {
        calls.incrementAndGet();
        lastTurns = List.copyOf(turns);
        Script queuedScript = queued.poll();
        Script current = queuedScript != null ? queuedScript : script;
        sleep(current.delayBeforeFirstMillis());
        boolean first = true;
        for (String delta : current.deltas()) {
            if (!first) {
                sleep(current.delayPerDeltaMillis());
            }
            first = false;
            onDelta.accept(delta);
        }
        if (current.failure() != null) {
            throw current.failure();
        }
        return new ModelResult(10, 20, "stop");
    }

    @Override
    public String modelName() {
        return "scripted";
    }

    @Override
    public int usageSource() {
        return 2;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("假模型被中断", ex);
        }
    }
}
