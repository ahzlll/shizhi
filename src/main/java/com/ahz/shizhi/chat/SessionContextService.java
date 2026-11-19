package com.ahz.shizhi.chat;

import com.ahz.shizhi.account.UserModelConfigService;
import com.ahz.shizhi.account.dto.ModelConfigView;
import com.ahz.shizhi.chat.dto.SessionContextView;
import com.ahz.shizhi.model.ModelProperties;
import org.springframework.stereotype.Service;

/**
 * 上下文占用的计算。
 *
 * <p>口径是「最近一次调用实际送进模型 + 生成回来的 token」，也就是模型此刻眼里的对话规模；
 * 不用累加所有历史消息——历史会被长度限制裁剪，累加值只会越飘越远。
 */
@Service
public class SessionContextService {

    /** token 里的中文占比高时按字数估算误差太大，所以优先读 usage；没有 usage 才退回估算。 */
    private static final double CHARS_PER_TOKEN = 2.0;

    private final MessageRepository messages;
    private final SessionService sessions;
    private final UserModelConfigService modelConfigs;
    private final ModelProperties modelProperties;
    private final ChatProperties chatProperties;

    public SessionContextService(MessageRepository messages,
                                 SessionService sessions,
                                 UserModelConfigService modelConfigs,
                                 ModelProperties modelProperties,
                                 ChatProperties chatProperties) {
        this.messages = messages;
        this.sessions = sessions;
        this.modelConfigs = modelConfigs;
        this.modelProperties = modelProperties;
        this.chatProperties = chatProperties;
    }

    public SessionContextView describe(long userId, long sessionId) {
        sessions.requireOwnership(userId, sessionId);

        ModelConfigView config = modelConfigs.view(userId);
        boolean custom = config.configured();
        String model = custom && config.model() != null ? config.model() : modelProperties.name();

        int used = messages.latestUsageTokens(sessionId)
                .orElseGet(() -> (int) Math.ceil(messages.activeChars(sessionId) / CHARS_PER_TOKEN));

        int window = chatProperties.contextWindow();
        double percent = window <= 0 ? 0 : Math.round(used * 1000.0 / window) / 10.0;
        return new SessionContextView(model, used, window, Math.min(percent, 100), custom);
    }
}
