package com.ahz.shizhi.chat;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 流式对话的可调参数。
 *
 * <p>心跳间隔 15 秒对应 04 §4.5 的硬要求：Nginx 默认 {@code proxy_read_timeout 60s}，
 * 模型中途停顿超过 60 秒，连接会被网关掐断，用户看到的就是莫名其妙的"中断"。
 *
 * <p>双阈值 flush 默认 2 秒 / 40 字（见 05「消息生命周期与中断收敛」）。做成配置项是为了让
 * 集成测试能把阈值压到毫秒级——否则验证"flush 过的内容还在"要等两秒以上，一组用例就慢十几秒。
 */
@ConfigurationProperties(prefix = "shizhi.chat")
public record ChatProperties(
        Duration heartbeatInterval,
        Duration flushInterval,
        int flushMinChars,
        Duration emitterTimeout
) {

    public ChatProperties {
        if (heartbeatInterval == null) {
            heartbeatInterval = Duration.ofSeconds(15);
        }
        if (flushInterval == null) {
            flushInterval = Duration.ofSeconds(2);
        }
        if (flushMinChars <= 0) {
            flushMinChars = 40;
        }
        if (emitterTimeout == null) {
            // 比模型总超时（180s）再宽 60 秒：模型侧宁可先超时收敛，也别让 SseEmitter 先掐断
            emitterTimeout = Duration.ofSeconds(240);
        }
    }
}
