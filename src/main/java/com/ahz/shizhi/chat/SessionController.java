package com.ahz.shizhi.chat;

import java.util.List;

import com.ahz.shizhi.auth.CurrentUser;
import com.ahz.shizhi.auth.UserPrincipal;
import com.ahz.shizhi.chat.dto.BatchSessionRequest;
import com.ahz.shizhi.chat.dto.CreateSessionRequest;
import com.ahz.shizhi.chat.dto.MessageView;
import com.ahz.shizhi.chat.dto.PinSessionRequest;
import com.ahz.shizhi.chat.dto.SendMessageRequest;
import com.ahz.shizhi.chat.dto.SessionContextView;
import com.ahz.shizhi.chat.dto.SessionView;
import com.ahz.shizhi.chat.dto.UpdateSessionRequest;
import com.ahz.shizhi.common.api.ApiResponse;
import com.ahz.shizhi.common.api.CursorPage;
import com.ahz.shizhi.common.api.PageResult;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final SessionContextService contextService;
    private final ChatStreamService chatStreamService;

    public SessionController(SessionService sessionService,
                             SessionContextService contextService,
                             ChatStreamService chatStreamService) {
        this.sessionService = sessionService;
        this.contextService = contextService;
        this.chatStreamService = chatStreamService;
    }

    /**
     * 新建会话。body 可带可不带——前端进聊天页先建空会话是最常见的路径。
     * {@code title} 此刻为空，发第一条消息时才截取问题的前 30 个字。
     */
    @PostMapping
    public ApiResponse<SessionView> create(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                          @Valid @RequestBody(required = false) CreateSessionRequest request) {
        return ApiResponse.ok(sessionService.create(principal.userId(), request));
    }

    /** 改名。只改标题，不动消息与活跃时间。 */
    @PatchMapping("/{id}")
    public ApiResponse<SessionView> rename(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                           @PathVariable long id,
                                           @Valid @RequestBody UpdateSessionRequest request) {
        return ApiResponse.ok(sessionService.rename(principal.userId(), id, request.title()));
    }

    /** 关键词搜会话：标题或消息正文命中。 */
    @GetMapping("/search")
    public ApiResponse<List<SessionView>> search(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @RequestParam String q) {
        return ApiResponse.ok(sessionService.search(principal.userId(), q));
    }

    /** 当前会话的模型与上下文占用。 */
    @GetMapping("/{id}/context")
    public ApiResponse<SessionContextView> context(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @PathVariable long id) {
        return ApiResponse.ok(contextService.describe(principal.userId(), id));
    }

    /** 批量移入回收站。 */
    @PostMapping("/batch/delete")
    public ApiResponse<Integer> batchDelete(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @Valid @RequestBody BatchSessionRequest request) {
        return ApiResponse.ok(sessionService.deleteBatch(principal.userId(), request.ids()));
    }

    /** 批量恢复。 */
    @PostMapping("/batch/restore")
    public ApiResponse<Integer> batchRestore(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @Valid @RequestBody BatchSessionRequest request) {
        return ApiResponse.ok(sessionService.restoreBatch(principal.userId(), request.ids()));
    }

    /** 批量彻底删除。 */
    @PostMapping("/batch/purge")
    public ApiResponse<Integer> batchPurge(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @Valid @RequestBody BatchSessionRequest request) {
        return ApiResponse.ok(sessionService.purgeBatch(principal.userId(), request.ids()));
    }

    /** 回收站列表，按删除时间倒序。 */
    @GetMapping("/trash")
    public ApiResponse<List<SessionView>> trash(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(sessionService.trash(principal.userId()));
    }

    /** 从回收站恢复。 */
    @PostMapping("/{id}/restore")
    public ApiResponse<SessionView> restore(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @PathVariable long id) {
        return ApiResponse.ok(sessionService.restore(principal.userId(), id));
    }

    /** 彻底删除，只对回收站里的会话生效。 */
    @DeleteMapping("/{id}/purge")
    public ApiResponse<Void> purge(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                   @PathVariable long id) {
        sessionService.purge(principal.userId(), id);
        return ApiResponse.ok(null);
    }

    /** 置顶 / 取消置顶。 */
    @PatchMapping("/{id}/pin")
    public ApiResponse<SessionView> pin(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                        @PathVariable long id,
                                        @Valid @RequestBody PinSessionRequest request) {
        return ApiResponse.ok(sessionService.pin(principal.userId(), id, request.pinned()));
    }

    /** 删除会话连同它的消息。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                                    @PathVariable long id) {
        sessionService.delete(principal.userId(), id);
        return ApiResponse.ok(null);
    }

    /** 会话列表。按最后活跃时间倒序，新建的会话立刻排在最前。 */
    @GetMapping
    public ApiResponse<PageResult<SessionView>> list(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = DEFAULT_PAGE) @Min(value = 1, message = "不能小于 1") int page,
            @RequestParam(defaultValue = DEFAULT_SIZE)
            @Min(value = 1, message = "不能小于 1")
            @Max(value = 100, message = "不能大于 100") int size) {
        return ApiResponse.ok(sessionService.list(principal.userId(), page, size));
    }

    /**
     * 发消息，SSE 流式返回。
     *
     * <p>校验失败与会话不存在都在这里同步抛出，此时还没开始推流，所以客户端拿到的是普通 JSON
     * （1000 / 2004 / 1004）——一个已经建立的 SSE 连接是没法回 400 的。
     */
    @PostMapping("/{id}/messages")
    public SseEmitter send(@Parameter(hidden = true) @CurrentUser UserPrincipal principal,
                           @PathVariable long id,
                           @Valid @RequestBody SendMessageRequest request) {
        return chatStreamService.stream(principal.userId(), id, request.content());
    }

    /**
     * 历史消息，游标分页。返回的 list 是时间正序，前端拿到就能直接渲染；
     * {@code nextCursor} 传回来取更早的一页。
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<CursorPage<MessageView>> messages(
            @Parameter(hidden = true) @CurrentUser UserPrincipal principal,
            @PathVariable long id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE)
            @Min(value = 1, message = "不能小于 1")
            @Max(value = 100, message = "不能大于 100") int size) {
        return ApiResponse.ok(sessionService.messages(principal.userId(), id, cursor, size));
    }
}
