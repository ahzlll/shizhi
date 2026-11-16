package com.ahz.shizhi.chat;

/**
 * 客户端断开时用来中断生成的内部控制流。
 *
 * <p>用异常而不是返回码，是因为它要穿过模型客户端的增量回调栈才能生效——
 * 这里抛出去，模型客户端读流的那层 {@code finally} 会关掉上游连接，生成立刻停下，
 * 不会让一个没人看的回答继续烧 token。
 *
 * <p>公开可见是为了让模型客户端的测试能抛同一个类型：{@link com.ahz.shizhi.model.ChatModelClient}
 * 的契约是「回调抛什么都原样传播、不许被吞成模型错误」，断线和模型报错混成一件事，
 * 就会被记成 5001 并把 {@code interrupt_reason} 写错。
 */
public class ClientClosedException extends RuntimeException {

    public ClientClosedException() {
        super("客户端已断开", null, false, false);
    }
}
