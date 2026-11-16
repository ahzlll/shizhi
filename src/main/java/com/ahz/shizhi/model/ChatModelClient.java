package com.ahz.shizhi.model;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型客户端。刻意做成阻塞式：调用线程一直占着，每来一段就回调一次。
 *
 * <p>做成阻塞而不是返回 {@code Flux} 之类，是因为整条链路本来就是"一个后台线程 + 一个 SseEmitter"，
 * 引响应式栈只会为了一个流而重写全项目的编程模型。阻塞式也让"客户端断开就抛异常"这件事
 * 变成自然的控制流，而不是一个订阅取消信号。
 */
public interface ChatModelClient {

    /**
     * 阻塞到生成结束，每来一段就回调一次。
     *
     * <p><b>实现方必须遵守的两条契约</b>——下游的行为依赖它们，破坏其中任何一条都会让状态写错：
     *
     * <ol>
     *   <li>{@code onDelta} 抛出的异常<b>原样向上传播</b>，不许包装成 {@link ModelException}。
     *       下游用 {@link com.ahz.shizhi.chat.ClientClosedException} 表达「用户关掉了页面」；
     *       一旦它被包成模型错误，断线就会被记成 5001、{@code interrupt_reason} 写成 MODEL_ERROR——
     *       用户关了个页面，服务端却以为模型坏了。</li>
     *   <li>无论正常结束还是抛异常，都<b>必须在 finally 里关掉上游连接</b>。回调抛了异常却把流留着，
     *       上游会继续生成、继续计费，而那份回答没有任何人接收。</li>
     * </ol>
     *
     * @param turns   完整的对话（含 system），按时间正序
     * @param onDelta 增量文本回调。实现方保证不回调空串；回调抛异常表示下游不要了，应立即停止生成
     * @return token 用量与结束原因
     * @throws ModelException   上游错误、限流、超时
     * @throws RuntimeException 回调自己抛出的异常，原样穿出（见上面第 1 条）
     */
    ModelResult stream(List<Turn> turns, Consumer<String> onDelta);

    /** 写进 ops_usage.model 的名字，便于事后按模型统计成本。 */
    default String modelName() {
        return "unknown";
    }

    /**
     * token 用量的来源：1 供应商返回 / 2 本地估算（见 03 §3.3 ops_usage.usage_source）。
     * 假模型永远只能估算，真模型的 usage 是上游给的。
     */
    default int usageSource() {
        return 1;
    }
}
