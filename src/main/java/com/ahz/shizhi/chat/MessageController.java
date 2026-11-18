package com.ahz.shizhi.chat;

import com.ahz.shizhi.auth.CurrentUser;
import com.ahz.shizhi.auth.UserPrincipal;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 消息级操作。会话级接口在 {@link SessionController}，这里只放「对某条已有消息做事」的接口，
 * 两者的资源不同：前者创建会话与回合，后者针对单条回答。
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final ChatStreamService chatStreamService;

    public MessageController(ChatStreamService chatStreamService) {
        this.chatStreamService = chatStreamService;
    }

    /**
     * 重试一条被中断或失败的回答。返回的事件序列与发消息完全一致，
     * 前端可以复用同一套解析；差别只在于这次不新增用户提问。
     *
     * <p>校验（消息不存在 / 不是自己的 / 状态不允许 / 重试链已满）全部在开流之前完成，
     * 所以这些失败是普通 JSON，而不是一个已经建立的流。
     */
    @PostMapping("/{id}/regenerate")
    public SseEmitter regenerate(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                 @PathVariable long id) {
        return chatStreamService.regenerate(principal.userId(), id);
    }
}
