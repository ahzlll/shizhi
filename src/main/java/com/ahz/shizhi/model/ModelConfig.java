package com.ahz.shizhi.model;

import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 {@code shizhi.model.provider} 选一个模型客户端。
 *
 * <p>用 {@code @ConditionalOnProperty} 而不是 {@code @ConditionalOnMissingBean}：后者在普通
 * {@code @Configuration} 里的求值顺序和 {@code @TestConfiguration} 没有保证，测试里想换实现时
 * 时灵时不灵。这里改成"取值显式匹配"——写成别的值就意味着由外部（测试）提供 bean，
 * 两者不会同时被创建，也就没有二选一的歧义。
 */
@Configuration
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfig {

    @Bean
    @ConditionalOnProperty(name = "shizhi.model.provider", havingValue = "stub", matchIfMissing = true)
    public ChatModelClient stubChatModelClient() {
        return new StubChatClient();
    }

    @Bean
    @ConditionalOnProperty(name = "shizhi.model.provider", havingValue = "openai-compatible")
    public ChatModelClient openAiCompatibleChatClient(ModelProperties properties,
                                                     @Qualifier("chatScheduler") ScheduledExecutorService scheduler) {
        return new OpenAiCompatibleChatClient(properties, scheduler);
    }
}
