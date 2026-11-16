package com.ahz.shizhi.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ahz.shizhi.chat.dto.StreamEvent;
import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.common.exception.BusinessException;
import com.ahz.shizhi.config.AsyncConfig;
import com.ahz.shizhi.model.ChatModelClient;
import com.ahz.shizhi.model.ModelException;
import com.ahz.shizhi.model.ModelRateLimitException;
import com.ahz.shizhi.model.ModelResult;
import com.ahz.shizhi.model.ModelTimeoutException;
import com.ahz.shizhi.model.Turn;
import com.ahz.shizhi.usage.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话。目前只做「提问 → 逐字回答 → 落库」这一段，检索旧笔记和技能判定还没接。
 *
 * <p>整条链路的骨架是"<b>两行消息先落库，再开流</b>"：
 *
 * <ol>
 *   <li>归属校验与两条消息的 INSERT 都在返回 {@link SseEmitter} 之前同步做完——
 *       这样 403/404/参数错误仍然是一条普通的 JSON 响应，而不是一个已经开着的 SSE 连接；</li>
 *   <li>ack 之后才组装提示词、调模型，因为 ack 是前端唯一能拿到 assistantMessageId 的机会；</li>
 *   <li>写库动作的最后一步是 {@code stream_state=1}，它是整条链路的提交点。</li>
 * </ol>
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private static final String EVENT_ACK = "ack";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";
    private static final String EVENT_HEARTBEAT = "heartbeat";

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private static final int STREAM_GENERATING = 0;
    private static final int STREAM_COMPLETED = 1;
    private static final int STREAM_INTERRUPTED = 2;
    private static final int STREAM_FAILED = 3;

    private static final String REASON_CLIENT_CLOSED = "CLIENT_CLOSED";
    private static final String REASON_MODEL_ERROR = "MODEL_ERROR";
    private static final String REASON_SERVER_TIMEOUT = "SERVER_TIMEOUT";

    /** 原回答算 1、重试一次算 2，最多留 3 代；再往后历史里会堆满几乎一样的回答。 */
    private static final long MAX_RETRY_CHAIN_DEPTH = 3L;

    /** 会话标题取第一个问题的前 30 个字。 */
    private static final int TITLE_MAX_LENGTH = 30;

    private final SessionService sessionService;
    private final MessageRepository messages;
    private final UsageRepository usage;
    private final PromptBuilder promptBuilder;
    private final ChatModelClient model;
    private final ChatProperties chatProperties;
    private final TransactionTemplate transaction;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;

    public ChatStreamService(SessionService sessionService,
                             MessageRepository messages,
                             UsageRepository usage,
                             PromptBuilder promptBuilder,
                             ChatModelClient model,
                             ChatProperties chatProperties,
                             TransactionTemplate transaction,
                             @Qualifier(AsyncConfig.CHAT_STREAM_EXECUTOR) Executor executor,
                             @Qualifier(AsyncConfig.CHAT_SCHEDULER) ScheduledExecutorService scheduler) {
        this.sessionService = sessionService;
        this.messages = messages;
        this.usage = usage;
        this.promptBuilder = promptBuilder;
        this.model = model;
        this.chatProperties = chatProperties;
        this.transaction = transaction;
        this.executor = executor;
        this.scheduler = scheduler;
    }

    /**
     * 发消息。方法体里<b>只有校验和落库是同步的</b>，生成本身丢给独立线程池。
     *
     * @throws com.ahz.shizhi.common.exception.BusinessException 会话不存在（2004）/ 不是自己的（1004），
     *        抛在返回 emitter 之前，所以一定是 JSON 响应
     */
    public SseEmitter stream(long userId, long sessionId, String content) {
        sessionService.requireOwnership(userId, sessionId);

        String question = content.strip();
        TurnIds ids = persistTurn(userId, sessionId, question);
        return startStreaming(userId, sessionId, ids, null);
    }

    /**
     * 重试一条被中断或失败的回答。
     *
     * <p>与发消息不同的是：不新增用户提问，而是插一条新的 assistant 行并把旧行标记为被替代。
     * 新行是全新的一行而不是原地重写——复用同一个消息 id 会撞上「按 messageId 派发的异步任务」
     * 那条唯一约束，第二次派发必然失败。
     *
     * @throws com.ahz.shizhi.common.exception.BusinessException 消息不存在（1001）/ 不是自己的（1004）/
     *         状态不允许重试（2009）/ 重试链已达上限（2010）。都抛在返回 emitter 之前，所以是普通 JSON
     */
    public SseEmitter regenerate(long userId, long messageId) {
        RetryPlan plan = prepareRetry(userId, messageId);
        return startStreaming(userId, plan.sessionId(), plan.ids(), plan.retryOfMessageId());
    }

    /**
     * 开流。发消息和重试共用的部分都收在这里，两条入口只负责把「这一轮的两个消息 id」准备好。
     *
     * @param retryOfMessageId 重试时指向被重试的旧行，首答为 null；失败且新行无产出时用它撤回替代关系
     */
    private SseEmitter startStreaming(long userId, long sessionId, TurnIds ids, Long retryOfMessageId) {
        SseEmitter emitter = new SseEmitter(chatProperties.emitterTimeout().toMillis());
        SseSink sink = new SseSink(emitter);
        emitter.onCompletion(sink::markClosed);
        emitter.onTimeout(sink::markClosed);
        emitter.onError(error -> sink.markClosed());

        executor.execute(() -> {
            try {
                generate(sink, userId, sessionId, ids, retryOfMessageId);
            } catch (RuntimeException ex) {
                // 走到这里是线程池拒绝、或收敛本身失败（比如数据库挂了）。
                // 消息行会停在 stream_state=0，由启动扫描、定时扫描和读时惰性收敛兜底成「中断」。
                log.error("流式生成线程异常退出 sessionId={} messageId={}", sessionId, ids.assistantMessageId(), ex);
            }
        });
        return emitter;
    }

    /**
     * 校验 + 在一个事务里插新行、标记旧行、更新会话计数。
     *
     * <p>并发双击靠 {@code markSuperseded} 的条件更新挡：两次请求都通过了读校验时，
     * 只有第一次能改到行，第二次影响行数为 0，于是整个事务回滚，不会留下两条新行。
     */
    private RetryPlan prepareRetry(long userId, long messageId) {
        MessageRepository.RetrySource source = messages.findRetrySource(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (source.userId() != userId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该消息");
        }
        if (!ROLE_ASSISTANT.equals(source.role())) {
            throw new BusinessException(ErrorCode.MESSAGE_ALREADY_COMPLETED, "该消息不可重试");
        }
        if (source.streamState() != STREAM_INTERRUPTED && source.streamState() != STREAM_FAILED) {
            throw new BusinessException(ErrorCode.MESSAGE_ALREADY_COMPLETED,
                    source.streamState() == STREAM_GENERATING ? "该回答还在生成中，不可重试" : "该回答已完成，无需重试");
        }
        if (source.supersededBy() != null) {
            throw new BusinessException(ErrorCode.MESSAGE_ALREADY_COMPLETED, "该回答已被重试");
        }
        if (messages.chainDepth(messageId) >= MAX_RETRY_CHAIN_DEPTH) {
            throw new BusinessException(ErrorCode.RETRY_LIMIT_EXCEEDED);
        }

        RetryPlan plan = transaction.execute(status -> {
            long newMessageId = messages.insertRetryPlaceholder(source.sessionId(), userId,
                    source.refNoteIds(), source.refConceptIds());
            if (messages.markSuperseded(messageId, newMessageId) == 0) {
                // 另一个请求抢先标记了旧行，这条新行没有存在意义，回滚掉
                throw new BusinessException(ErrorCode.MESSAGE_ALREADY_COMPLETED, "该回答已被重试");
            }
            messages.bumpSessionAfterRetry(source.sessionId());
            long userMessageId = messages.previousUserMessageId(source.sessionId(), messageId);
            if (userMessageId == 0) {
                // 找不到对应的提问就说明数据不对：把 id=0 发给前端只会让那个气泡永远挂不上任何问题
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到这条回答对应的提问");
            }
            return new RetryPlan(source.sessionId(),
                    new TurnIds(userMessageId, newMessageId), messageId);
        });
        if (plan == null) {
            throw new IllegalStateException("重试落库后没有拿到消息 id");
        }
        return plan;
    }

    /**
     * 两条消息 + 会话计数必须在一个事务里：用户消息落库了但占位行没落，
     * 用户就会看到"我说了话，系统没接住"。
     */
    private TurnIds persistTurn(long userId, long sessionId, String question) {
        TurnIds ids = transaction.execute(status -> {
            long userMessageId = messages.insertMessage(
                    sessionId, userId, ROLE_USER, question, STREAM_COMPLETED);
            long assistantMessageId = messages.insertMessage(
                    sessionId, userId, ROLE_ASSISTANT, "", STREAM_GENERATING);
            messages.bumpSessionAfterTurn(sessionId, titleFrom(question));
            return new TurnIds(userMessageId, assistantMessageId);
        });
        if (ids == null) {
            throw new IllegalStateException("落库一轮问答后没有拿到消息 id");
        }
        return ids;
    }

    private void generate(SseSink sink, long userId, long sessionId, TurnIds ids, Long retryOfMessageId) {
        long startedAt = System.currentTimeMillis();
        AtomicLong firstTokenAt = new AtomicLong(-1L);
        AtomicInteger emittedChars = new AtomicInteger();
        StreamBuffer buffer = new StreamBuffer(
                chatProperties.flushMinChars(),
                chatProperties.flushInterval().toMillis(),
                System::currentTimeMillis);

        ScheduledFuture<?> heartbeat = startHeartbeat(sink);
        try {
            if (!sink.send(EVENT_ACK, new StreamEvent.Ack(
                    ids.userMessageId(), ids.assistantMessageId(), STREAM_GENERATING))) {
                throw new ClientClosedException();
            }

            List<Turn> prompt = promptBuilder.build(
                    messages.recentTurns(sessionId, PromptBuilder.HISTORY_MESSAGE_LIMIT));

            ModelResult result = model.stream(prompt, delta -> {
                if (!sink.send(EVENT_DELTA, new StreamEvent.Delta(delta))) {
                    // 客户端已经走了。继续生成只会烧 token，而且没人收——立刻停
                    throw new ClientClosedException();
                }
                firstTokenAt.compareAndSet(-1L, System.currentTimeMillis());
                emittedChars.addAndGet(delta.length());
                buffer.append(delta);
                if (buffer.isDue()) {
                    flush(ids.assistantMessageId(), buffer);
                }
            });

            // 用量先记：stream_state=1 必须是所有写库动作里的最后一个，
            // 否则崩在中间就是"消息标记完成、成本没记上"，且没有任何信号
            recordUsage(userId, result.promptTokens(), result.completionTokens(), startedAt,
                    firstTokenAt, result.finishReason(), true);

            // 提交点：末尾剩余内容 + stream_state=1 一次写完，且是最后一个写库动作
            messages.markCompleted(ids.assistantMessageId(), buffer.pendingText(),
                    result.promptTokens(), result.completionTokens());
            buffer.commit();

            sink.send(EVENT_DONE, new StreamEvent.Done(ids.assistantMessageId(),
                    result.promptTokens(), result.completionTokens(),
                    System.currentTimeMillis() - startedAt, result.finishReason()));
        } catch (ClientClosedException ex) {
            log.info("客户端断开，收敛为中断 sessionId={} messageId={}", sessionId, ids.assistantMessageId());
            recordUsage(userId, 0, 0, startedAt, firstTokenAt, REASON_CLIENT_CLOSED, false);
            // 断开时不补写缓冲区：已经落库的留下，没来得及写的就丢了，最多丢一个 flush 周期。
            messages.markInterrupted(ids.assistantMessageId(), null, REASON_CLIENT_CLOSED);
            restorePreviousIfEmpty(retryOfMessageId, ids.assistantMessageId(), emittedChars.get());
        } catch (ModelTimeoutException ex) {
            log.warn("模型超时 sessionId={} messageId={} reason={}",
                    sessionId, ids.assistantMessageId(), ex.getMessage());
            convergeFailure(sink, userId, ids, buffer, emittedChars, startedAt, firstTokenAt,
                    retryOfMessageId, REASON_SERVER_TIMEOUT, ex.errorCode());
        } catch (ModelRateLimitException ex) {
            log.warn("模型限流 sessionId={} messageId={}", sessionId, ids.assistantMessageId());
            convergeFailure(sink, userId, ids, buffer, emittedChars, startedAt, firstTokenAt,
                    retryOfMessageId, REASON_MODEL_ERROR, ex.errorCode());
        } catch (ModelException ex) {
            log.warn("模型调用失败 sessionId={} messageId={} reason={}",
                    sessionId, ids.assistantMessageId(), ex.getMessage());
            convergeFailure(sink, userId, ids, buffer, emittedChars, startedAt, firstTokenAt,
                    retryOfMessageId, REASON_MODEL_ERROR, ex.errorCode());
        } catch (RuntimeException ex) {
            log.error("生成过程中出现未预期异常 sessionId={} messageId={}",
                    sessionId, ids.assistantMessageId(), ex);
            convergeFailure(sink, userId, ids, buffer, emittedChars, startedAt, firstTokenAt,
                    retryOfMessageId, REASON_MODEL_ERROR, ErrorCode.MODEL_CALL_FAILED);
        } finally {
            stopHeartbeat(heartbeat);
            sink.complete();
        }
    }

    /**
     * 失败收敛。判 2 还是 3，看的是<b>这次已经吐出去多少字</b>，不是去查 {@code content}：
     * 正文为空既可能是"还没写"，也可能是"模型返回了空回答"，反推不出状态。
     * 也不能看"缓冲区此刻是否为空"——它每次 flush 后都会清空，刚 flush 完就出错会被误判成没吐过字。
     */
    private void convergeFailure(SseSink sink, long userId, TurnIds ids, StreamBuffer buffer,
                                 AtomicInteger emittedChars, long startedAt, AtomicLong firstTokenAt,
                                 Long retryOfMessageId, String reason, ErrorCode code) {
        boolean emitted = emittedChars.get() > 0;
        recordUsage(userId, 0, 0, startedAt, firstTokenAt, reason, false);
        try {
            if (emitted) {
                messages.markInterrupted(ids.assistantMessageId(), buffer.pendingText(), reason);
            } else {
                messages.markFailed(ids.assistantMessageId(), reason);
            }
        } catch (DataAccessException ex) {
            // 收敛失败也不能让 error 事件推不出去：用户至少要知道这次没成功
            log.error("收敛消息状态失败 messageId={}", ids.assistantMessageId(), ex);
        }
        restorePreviousIfEmpty(retryOfMessageId, ids.assistantMessageId(), emittedChars.get());
        sink.send(EVENT_ERROR, new StreamEvent.Error(ids.assistantMessageId(), code.code(), code.message()));
    }

    /**
     * 重试出来的一行一个字都没产出时，把旧行的「已被替代」撤回。
     *
     * <p>否则用户点重试、模型挂了，连原来那条中断的半截回答也一起从历史里消失——
     * 等于重试把历史弄丢了。只在新行无产出时撤回：有产出的新行本身就是更好的结果。
     */
    private void restorePreviousIfEmpty(Long retryOfMessageId, long newMessageId, int emittedChars) {
        if (retryOfMessageId == null || emittedChars > 0) {
            return;
        }
        try {
            int restored = messages.clearSuperseded(retryOfMessageId, newMessageId);
            if (restored > 0) {
                log.info("重试无产出，已恢复原回答可见 messageId={}", retryOfMessageId);
            }
        } catch (DataAccessException ex) {
            log.warn("恢复原回答可见失败 messageId={}", retryOfMessageId, ex);
        }
    }

    /**
     * 周期 flush。失败只记日志、不中断生成——数据库抖一下不该毁掉一次已经生成了一半的回答。
     */
    private void flush(long messageId, StreamBuffer buffer) {
        String pending = buffer.pendingText();
        if (pending.isEmpty()) {
            return;
        }
        try {
            int updated = messages.appendContent(messageId, pending);
            if (updated == 0) {
                // 行已经不是"生成中"了（被读时收敛成 2），继续写只会和状态打架
                log.info("消息已不在生成中，本轮 flush 落空 messageId={}", messageId);
            }
            buffer.commit();
        } catch (DataAccessException ex) {
            log.warn("flush 消息内容失败，继续生成 messageId={}", messageId, ex);
        }
    }

    private ScheduledFuture<?> startHeartbeat(SseSink sink) {
        long period = chatProperties.heartbeatInterval().toMillis();
        if (period <= 0) {
            return null;
        }
        return scheduler.scheduleAtFixedRate(
                () -> sink.send(EVENT_HEARTBEAT, Map.of()), period, period, TimeUnit.MILLISECONDS);
    }

    /** done/error/断开之后必须停掉，否则每个回答都会留下一个永不结束的心跳任务。 */
    private void stopHeartbeat(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    private void recordUsage(long userId, int promptTokens, int completionTokens, long startedAt,
                             AtomicLong firstTokenAt, String finishReason, boolean success) {
        Integer firstTokenMs = firstTokenAt.get() < 0
                ? null
                : (int) (firstTokenAt.get() - startedAt);
        try {
            usage.insertChatCall(new UsageRepository.ChatUsage(
                    userId,
                    model.modelName(),
                    promptTokens,
                    completionTokens,
                    (int) (System.currentTimeMillis() - startedAt),
                    firstTokenMs,
                    finishReason,
                    model.usageSource(),
                    success));
        } catch (DataAccessException ex) {
            // 用量没记上不该连累用户已经拿到的回答
            log.warn("记录模型用量失败 userId={}", userId, ex);
        }
    }

    /**
     * 取前 30 <b>个字</b>。这里必须按 code point 数，不能按 {@code length()}/{@code substring()}——
     * 那两个数的是 UTF-16 char，emoji 和 CJK 扩展区都是代理对，第 30 个 char 落在代理对中间时
     * 会截出一个孤立高代理，写库时被 UTF-8 编码替换成 {@code '?'}，标题末尾就挂个问号。
     */
    private static String titleFrom(String question) {
        if (question.codePointCount(0, question.length()) <= TITLE_MAX_LENGTH) {
            return question;
        }
        return question.substring(0, question.offsetByCodePoints(0, TITLE_MAX_LENGTH));
    }

    /** 一轮问答的两个消息 id。ack 事件要同时下发它们，前端才能把气泡和历史对上。 */
    private record TurnIds(long userMessageId, long assistantMessageId) {
    }

    /**
     * 重试落库后的结果：会话、这一轮的两个 id、以及被重试的旧行 id（失败无产出时用来撤回替代）。
     */
    private record RetryPlan(long sessionId, TurnIds ids, long retryOfMessageId) {
    }
}
