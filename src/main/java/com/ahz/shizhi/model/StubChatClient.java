package com.ahz.shizhi.model;

import java.util.List;
import java.util.function.Consumer;

import com.ahz.shizhi.common.api.ErrorCode;

/**
 * 本地假模型：吐一段固定文本，每 40ms 一个字。
 *
 * <p>它不是"测试替身"，而是**没有 API key 时的默认运行模式**：整条流式链路（占位行、delta、
 * 双阈值 flush、心跳、终态收敛）都能被真切地跑一遍，只有"文本是不是模型写的"这一件事是假的。
 * 每 40ms 一个字是刻意选的——足够慢到能看见流式效果，也足够快到一次回答在两秒内结束。
 */
public class StubChatClient implements ChatModelClient {

    private static final String SCRIPT = "这是本地假模型的回答。没有配置模型 API key 时，用它验证流式链路是否通。";
    private static final long DELAY_MILLIS = 40L;
    private static final int CHARS_PER_TOKEN = 3;

    @Override
    public ModelResult stream(List<Turn> turns, Consumer<String> onDelta) {
        for (int i = 0; i < SCRIPT.length(); i++) {
            if (i > 0) {
                sleepBeforeNextChar();
            }
            onDelta.accept(SCRIPT.substring(i, i + 1));
        }
        int promptTokens = estimateTokens(promptChars(turns));
        int completionTokens = estimateTokens(SCRIPT.length());
        return new ModelResult(promptTokens, completionTokens, "stop");
    }

    @Override
    public String modelName() {
        return "stub";
    }

    @Override
    public int usageSource() {
        return 2;
    }

    /** 中断（客户端断开）时线程会被打断，转成 ModelException 让上层按"没说完"收敛。 */
    private static void sleepBeforeNextChar() {
        try {
            Thread.sleep(DELAY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED, "假模型被中断", ex);
        }
    }

    private static int promptChars(List<Turn> turns) {
        int chars = 0;
        for (Turn turn : turns) {
            chars += turn.content() == null ? 0 : turn.content().length();
        }
        return chars;
    }

    /**
     * 粗略估算：1 token 约 3 个字符。真模型这条路径不会走到这里（上游会返回 usage），
     * 估算值只在 usage_source=2 时出现，用途是大致统计，不参与计价。
     */
    private static int estimateTokens(int chars) {
        return Math.max(1, (int) Math.ceil(chars / (double) CHARS_PER_TOKEN));
    }
}
