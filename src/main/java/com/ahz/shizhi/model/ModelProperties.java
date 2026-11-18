package com.ahz.shizhi.model;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置。provider 与 api-key 都从环境变量注入，默认值是"能起、但不接真模型"——
 * 本地和 CI 没有 key 时必须能启动，否则每个测试类都要配一遍环境变量。
 *
 * @param provider           {@code stub}（本地假模型）/ {@code openai-compatible}（兼容 OpenAI 协议的云端服务）
 * @param baseUrl            上游根地址，代码会拼 {@code /chat/completions}
 * @param apiKey             空表示没配，启动不拦，真正调用时给出明确错误
 * @param name               主模型名，写进 ops_usage.model
 * @param fallbackName       备用模型名：主模型限流或报错、且这一轮还没吐过正文时改用它。空表示不降级
 * @param firstTokenTimeout  首字超时：从发起请求到收到<b>第一个数据块</b>（思考块也算）
 * @param idleTimeout        空闲超时：两个数据块之间最长间隔
 * @param totalTimeout       总时长上限
 */
@ConfigurationProperties(prefix = "shizhi.model")
public record ModelProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String name,
        String fallbackName,
        Duration firstTokenTimeout,
        Duration idleTimeout,
        Duration totalTimeout
) {

    public ModelProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stub";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        if (name == null || name.isBlank()) {
            name = "deepseek-chat";
        }
        if (firstTokenTimeout == null) {
            firstTokenTimeout = Duration.ofSeconds(15);
        }
        if (idleTimeout == null) {
            idleTimeout = Duration.ofSeconds(60);
        }
        if (totalTimeout == null) {
            totalTimeout = Duration.ofMinutes(3);
        }
    }
}
