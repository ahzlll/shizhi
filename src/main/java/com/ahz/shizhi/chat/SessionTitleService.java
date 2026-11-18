package com.ahz.shizhi.chat;

import java.util.List;

import com.ahz.shizhi.model.ChatModelClient;
import com.ahz.shizhi.model.Turn;
import com.ahz.shizhi.model.UserModelClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 让模型给会话起标题，替代「截取问题前 30 字」。
 *
 * <p>只在第一轮回答结束后跑一次，失败就保留原来的截断标题——标题是锦上添花，
 * 不能因为它失败让用户看到错误。花费的是十几个 token，量级可以忽略。
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    private static final int MAX_TITLE_LENGTH = 20;
    /** 模型偶尔会多吐几句，收够就停，别把整段解释当标题。 */
    private static final int COLLECT_LIMIT = 60;

    private static final String PROMPT = """
            你负责给对话起一个标题。要求：
            1. 不超过 12 个字；
            2. 只输出标题本身，不要引号、书名号、句号，也不要解释；
            3. 用陈述短语，不要用「关于……的讨论」这类空话。""";

    private final SessionRepository sessions;
    private final UserModelClientFactory modelClients;

    public SessionTitleService(SessionRepository sessions, UserModelClientFactory modelClients) {
        this.sessions = sessions;
        this.modelClients = modelClients;
    }

    public void generate(long userId, long sessionId, String question) {
        try {
            ChatModelClient client = modelClients.resolve(userId);
            StringBuilder collected = new StringBuilder();
            List<Turn> prompt = List.of(Turn.system(PROMPT), Turn.user(question));

            client.stream(prompt, delta -> {
                if (collected.length() < COLLECT_LIMIT) {
                    collected.append(delta);
                }
            });

            String title = clean(collected.toString());
            if (!title.isEmpty()) {
                // 第一轮才调用，直接覆盖 persistTurn 写下的截断标题
                sessions.updateTitle(sessionId, userId, title);
            }
        } catch (RuntimeException ex) {
            // 起标题失败不影响任何主流程，保留「问题前 30 字」那个兜底标题
            log.warn("生成会话标题失败 sessionId={}", sessionId, ex);
        }
    }

    private static String clean(String raw) {
        String title = raw.strip()
                .replaceAll("^[\"'“”「『《]+", "")
                .replaceAll("[\"'“”」』》。.!！?？\\s]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }
}
