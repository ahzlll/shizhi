package com.ahz.shizhi.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.ahz.shizhi.model.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    /** 2 字符 = 1 token，用固定长度造消息，预算才好算。 */
    private static Turn turn(String role, int tokens) {
        return new Turn(role, "x".repeat(tokens * 2));
    }

    @Test
    @DisplayName("空历史：不纳入也不产生摘要")
    void emptyHistory() {
        ContextAssembler.Selection selection = assembler.select(List.of(), 100);

        assertThat(selection.included()).isEmpty();
        assertThat(selection.dropped()).isEmpty();
        assertThat(selection.usedTokens()).isZero();
    }

    @Test
    @DisplayName("全部装得下：原样纳入，顺序仍是从旧到新")
    void everythingFits() {
        List<Turn> history = List.of(turn("user", 10), turn("assistant", 10), turn("user", 10));

        ContextAssembler.Selection selection = assembler.select(history, 100);

        assertThat(selection.included()).containsExactlyElementsOf(history);
        assertThat(selection.dropped()).isEmpty();
        assertThat(selection.usedTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("预算刚好用完：不应被误判为超限")
    void exactBudgetBoundary() {
        List<Turn> history = List.of(turn("user", 30), turn("assistant", 20));

        ContextAssembler.Selection selection = assembler.select(history, 50);

        assertThat(selection.included()).hasSize(2);
        assertThat(selection.dropped()).isEmpty();
        assertThat(selection.usedTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("超出预算一条：这条和更旧的全部转摘要")
    void oneTokenOverBudget() {
        List<Turn> history = List.of(turn("user", 30), turn("assistant", 20), turn("user", 1));

        ContextAssembler.Selection selection = assembler.select(history, 50);

        // 最近一条 1 token 进得去，再往前 20 + 1 = 21 也进得去，30 进不去
        assertThat(selection.included()).hasSize(2);
        assertThat(selection.dropped()).hasSize(1);
        assertThat(selection.usedTokens()).isEqualTo(21);
    }

    @Test
    @DisplayName("只有一条且自己就超限：上下文为空，它单独走摘要")
    void singleMessageOverBudget() {
        List<Turn> history = List.of(turn("user", 80));

        ContextAssembler.Selection selection = assembler.select(history, 20);

        assertThat(selection.included()).isEmpty();
        assertThat(selection.dropped()).hasSize(1);
        assertThat(selection.usedTokens()).isZero();
    }

    @Test
    @DisplayName("最旧一条超限、最近的装得下：只丢最旧那条，不做跳条挑选")
    void oldestDroppedRecentKept() {
        List<Turn> history = List.of(turn("user", 80), turn("assistant", 5));

        ContextAssembler.Selection selection = assembler.select(history, 20);

        assertThat(selection.included()).hasSize(1);
        assertThat(selection.dropped()).hasSize(1);
        assertThat(selection.usedTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("每一条都超限：全部丢弃，不让一条半截消息混进上下文")
    void allMessagesOverBudget() {
        List<Turn> history = List.of(turn("user", 40), turn("assistant", 40));

        ContextAssembler.Selection selection = assembler.select(history, 10);

        assertThat(selection.included()).isEmpty();
        assertThat(selection.dropped()).hasSize(2);
        assertThat(selection.usedTokens()).isZero();
    }

    @Test
    @DisplayName("预算为 0：全部转摘要")
    void zeroBudget() {
        List<Turn> history = List.of(turn("user", 1));

        ContextAssembler.Selection selection = assembler.select(history, 0);

        assertThat(selection.included()).isEmpty();
        assertThat(selection.dropped()).hasSize(1);
    }

    @Test
    @DisplayName("token 估算：空串为 0，两个字符算一个")
    void tokenEstimation() {
        assertThat(ContextAssembler.estimateTokens("")).isZero();
        assertThat(ContextAssembler.estimateTokens(null)).isZero();
        assertThat(ContextAssembler.estimateTokens("ab")).isEqualTo(1);
        assertThat(ContextAssembler.estimateTokens("abc")).isEqualTo(2);
    }
}
