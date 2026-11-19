package com.ahz.shizhi.chat;

import java.util.ArrayList;
import java.util.List;

import com.ahz.shizhi.model.Turn;
import org.springframework.stereotype.Component;

/**
 * 组装提示词：系统身份 + 历史摘要（可选）+ 落在预算内的最近若干条。
 *
 * <p>完整形态是四段（身份 / 技能 / 用户知识上下文 / 对话历史），后两段等检索和技能判定做完再接。
 */
@Component
public class PromptBuilder {

    private static final String SYSTEM_PROMPT = "你是用户的学习助手，回答要准确、简洁。";

    public List<Turn> build(String historySummary, List<Turn> history) {
        List<Turn> turns = new ArrayList<>(history.size() + 2);
        turns.add(Turn.system(SYSTEM_PROMPT));
        if (historySummary != null && !historySummary.isBlank()) {
            turns.add(Turn.system("以下是对更早对话的摘要，可作为背景参考：\n" + historySummary.strip()));
        }
        turns.addAll(history);
        return turns;
    }
}
