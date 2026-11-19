package com.ahz.shizhi.chat;

import java.util.ArrayList;
import java.util.List;

import com.ahz.shizhi.model.Turn;
import org.springframework.stereotype.Component;

/**
 * 上下文预算。
 *
 * <p>从最近一条消息往前累加，装不下就停：这一条和更旧的全部让给摘要。
 * 计量单位是 token，估算方式与 {@link SessionContextService} 的兜底一致（2 字符约 1 token），
 * 这样界面上显示的占比和这里用的预算才是同一个口径。
 */
@Component
public class ContextAssembler {

    /** 中英文混排下粗估：两个字符算一个 token。 */
    private static final double CHARS_PER_TOKEN = 2.0;

    /**
     * @param included   真正送进模型的最近若干条，按时间正序
     * @param dropped    装不下、需要另做摘要的更早消息，按时间正序
     * @param usedTokens 已纳入部分占用的 token 数
     * @param budget     本次预算
     */
    public record Selection(List<Turn> included, List<Turn> dropped, int usedTokens, int budget) {
    }

    /**
     * @param history 按时间正序的历史消息，最后一条是最近的
     * @param budget  本次可用的 token 预算
     */
    public Selection select(List<Turn> history, int budget) {
        if (history == null || history.isEmpty()) {
            return new Selection(List.of(), List.of(), 0, budget);
        }

        List<Turn> included = new ArrayList<>();
        int used = 0;
        int firstIncluded = history.size();

        for (int i = history.size() - 1; i >= 0; i--) {
            int cost = estimateTokens(history.get(i).content());
            // 装不下就整个停下：这条和更旧的一起交给摘要，不做跳条挑选，
            // 否则上下文里会出现“跳过中间某轮”的断层
            if (used + cost > budget) {
                break;
            }
            used += cost;
            firstIncluded = i;
        }

        included.addAll(history.subList(firstIncluded, history.size()));
        List<Turn> dropped = new ArrayList<>(history.subList(0, firstIncluded));
        return new Selection(included, dropped, used, budget);
    }

    public static int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(content.length() / CHARS_PER_TOKEN);
    }
}
