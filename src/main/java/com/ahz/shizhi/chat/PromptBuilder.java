package com.ahz.shizhi.chat;

import java.util.ArrayList;
import java.util.List;

import com.ahz.shizhi.model.Turn;
import org.springframework.stereotype.Component;

/**
 * 组装提示词。这一期只有两段：系统身份 + 最近 6 轮对话。
 *
 * <p>05「提示词组装的结构」定的是四段（身份 / 技能 / 用户知识上下文 / 对话历史），
 * 后两段要等检索和技能判定落地，所以这里刻意留空：结构对了，加段只是往中间插。
 */
@Component
public class PromptBuilder {

    /** 最近 6 轮 = 12 条消息。超出的部分要摘要压缩，那是二期的事，现在直接截断。 */
    public static final int HISTORY_MESSAGE_LIMIT = 12;

    /**
     * 系统身份的措辞跟 05 一致。等第 3 段（用户知识上下文）落地时，这段还要补一句
     * "以下是用户此前记录的内容"——那句措辞对"主动带出旧知识"的触发率影响很大。
     */
    private static final String SYSTEM_PROMPT = "你是用户的学习助手，回答要准确、简洁。";

    public List<Turn> build(List<Turn> history) {
        List<Turn> turns = new ArrayList<>(history.size() + 1);
        turns.add(Turn.system(SYSTEM_PROMPT));
        turns.addAll(history);
        return turns;
    }
}
