package com.ahz.shizhi.chat;

import com.ahz.shizhi.auth.CurrentUser;
import com.ahz.shizhi.auth.UserPrincipal;
import com.ahz.shizhi.chat.dto.CreateSessionRequest;
import com.ahz.shizhi.chat.dto.MessageView;
import com.ahz.shizhi.chat.dto.SessionView;
import com.ahz.shizhi.common.api.ApiResponse;
import com.ahz.shizhi.common.api.CursorPage;
import com.ahz.shizhi.common.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话与历史消息。
 *
 * <p>刻意不加类级 {@code @Validated}：那会走 AOP 代理抛 {@code ConstraintViolationException}，
 * 全局处理器没覆盖它，校验失败就成了 500。把 {@code @Min}/{@code @Max} 直接挂在
 * {@code @RequestParam} 上是 Spring 内置的方法参数校验，抛的 {@code HandlerMethodValidationException}
 * 全局处理器已经处理，能返回 1000 + 字段级 errors。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final String DEFAULT_PAGE = "1";
    private static final String DEFAULT_SIZE = "20";

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 新建会话。body 可带可不带——前端进聊天页先建空会话是最常见的路径。
     * {@code title} 此刻为空，发第一条消息时才截取问题前 30 字（docs/03 §3.3）。
     */
    @PostMapping
    public ApiResponse<SessionView> create(@CurrentUser UserPrincipal principal,
                                          @Valid @RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.ok(sessionService.create(principal.userId(), request));
    }

    /** 会话列表。按最后活跃时间倒序，新建的会话立刻排在最前。 */
    @GetMapping
    public ApiResponse<PageResult<SessionView>> list(
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = DEFAULT_PAGE) @Min(value = 1, message = "不能小于 1") int page,
            @RequestParam(defaultValue = DEFAULT_SIZE)
            @Min(value = 1, message = "不能小于 1")
            @Max(value = 100, message = "不能大于 100") int size) {
        return ApiResponse.ok(sessionService.list(principal.userId(), page, size));
    }

    /**
     * 历史消息，游标分页。返回的 list 是时间正序，前端拿到就能直接渲染；
     * {@code nextCursor} 传回来取更早的一页。
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<CursorPage<MessageView>> messages(
            @CurrentUser UserPrincipal principal,
            @PathVariable long id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE)
            @Min(value = 1, message = "不能小于 1")
            @Max(value = 100, message = "不能大于 100") int size) {
        return ApiResponse.ok(sessionService.messages(principal.userId(), id, cursor, size));
    }
}
