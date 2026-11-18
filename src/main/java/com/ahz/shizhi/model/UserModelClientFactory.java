package com.ahz.shizhi.model;

import java.util.concurrent.ScheduledExecutorService;

import com.ahz.shizhi.account.UserModelConfigService;
import com.ahz.shizhi.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 决定这次对话用哪套模型参数：用户自己配了就用他的，没配就回落到服务端默认。
 *
 * <p>每次请求新建一个客户端，不缓存。用户换 key 或换模型后应当立刻生效，
 * 缓存还要处理失效；而一个客户端的成本只是一个 HttpClient，个人规模下不值得为它引入缓存。
 */
@Component
public class UserModelClientFactory {

    private final ChatModelClient defaultClient;
    private final UserModelConfigService configs;
    private final ModelProperties defaults;
    private final ScheduledExecutorService scheduler;

    public UserModelClientFactory(ChatModelClient defaultClient,
                                  UserModelConfigService configs,
                                  ModelProperties defaults,
                                  @Qualifier(AsyncConfig.CHAT_SCHEDULER) ScheduledExecutorService scheduler) {
        this.defaultClient = defaultClient;
        this.configs = configs;
        this.defaults = defaults;
        this.scheduler = scheduler;
    }

    public ChatModelClient resolve(long userId) {
        return configs.resolve(userId)
                .<ChatModelClient>map(config -> new OpenAiCompatibleChatClient(propertiesFor(config), scheduler))
                .orElse(defaultClient);
    }

    private ModelProperties propertiesFor(UserModelConfigService.ResolvedModel config) {
        // 用户配置里不带备用模型：换模型是他自己的选择，失败就该如实报错，不要偷偷换成别的
        return new ModelProperties(
                "openai-compatible",
                config.baseUrl(),
                config.apiKey(),
                config.model(),
                null,
                defaults.firstTokenTimeout(),
                defaults.idleTimeout(),
                defaults.totalTimeout());
    }
}
