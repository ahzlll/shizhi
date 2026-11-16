package com.ahz.shizhi.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 模型用量的落库口。
 *
 * <p>单独一个包而不是塞进 chat：{@code scene} 这一个字段就把笔记沉淀、连边、技能分类、向量化
 * 全部收进来了，它本来就不是对话专属的表。这期只有 CHAT 在写，别的场景接进来时不用搬家。
 */
@Repository
public class UsageRepository {

    private static final String SCENE_CHAT = "CHAT";

    private static final String INSERT_USAGE =
            "INSERT INTO ops_usage (user_id, scene, model, prompt_tokens, completion_tokens,"
                    + " cost_micro, latency_ms, first_token_ms, finish_reason, usage_source, success)"
                    + " VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记一次对话调用。{@code cost_micro} 固定 0：计价规则还没定，
     * 先拍一个单价只会让后面的统计多一批假数据。
     */
    public void insertChatCall(ChatUsage usage) {
        jdbc.update(INSERT_USAGE,
                usage.userId(),
                SCENE_CHAT,
                usage.model(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.latencyMs(),
                usage.firstTokenMs(),
                usage.finishReason(),
                usage.usageSource(),
                usage.success());
    }

    /**
     * @param firstTokenMs 首字延迟；一个字都没出来时为 null（0 和"还没到"是两件事）
     */
    public record ChatUsage(
            long userId,
            String model,
            int promptTokens,
            int completionTokens,
            int latencyMs,
            Integer firstTokenMs,
            String finishReason,
            int usageSource,
            boolean success
    ) {
    }
}
