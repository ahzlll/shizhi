package com.ahz.shizhi.chat;

import java.util.List;

import com.ahz.shizhi.model.ChatModelClient;
import com.ahz.shizhi.model.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 给装不进预算的更早消息做摘要。
 *
 * <p>摘要是补充信息，不是必需品：模型调用失败就返回 null，让对话按剩下的历史继续，
 * 不能因为摘要失败把这一轮提问也搞挂。
 */
@Component
public class HistorySummarizer {

    private static final Logger log = LoggerFactory.getLogger(HistorySummarizer.class);

    /** 摘要长度上限，按字符数卡，够放下几条事实和待办。 */
    private static final int SUMMARY_CHAR_LIMIT = 400;

    private static final String PROMPT = """
            下面是一段较早的对话记录，因为长度限制不会完整传给模型。
            请用不超过 200 字概括，只保留：关键事实、已形成的结论或决定、待办事项、尚未解决的问题。
            不要复述寒暄，不要分段编号，只输出摘要正文。""";

    public String summarize(ChatModelClient client, List<Turn> dropped) {
        if (dropped == null || dropped.isEmpty()) {
            return null;
        }

        StringBuilder transcript = new StringBuilder();
        for (Turn turn : dropped) {
            String speaker = "user".equals(turn.role()) ? "用户" : "助手";
            transcript.append(speaker).append("：").append(turn.content()).append('\n');
        }

        StringBuilder summary = new StringBuilder();
        try {
            client.stream(
                    List.of(Turn.system(PROMPT), Turn.user(transcript.toString())),
                    delta -> {
                        if (summary.length() < SUMMARY_CHAR_LIMIT) {
                            summary.append(delta);
                        }
                    });
        } catch (RuntimeException ex) {
            log.warn("生成历史摘要失败，本轮不带摘要继续", ex);
            return null;
        }

        String text = summary.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > SUMMARY_CHAR_LIMIT ? text.substring(0, SUMMARY_CHAR_LIMIT) : text;
    }
}
