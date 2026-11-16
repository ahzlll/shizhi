package com.ahz.shizhi.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.config.AsyncConfig;
import com.ahz.shizhi.model.ModelException;
import com.ahz.shizhi.model.ModelTimeoutException;
import com.ahz.shizhi.model.Turn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 流式对话的端到端测试：真起服务、真发 HTTP、真读 SSE 字节流。
 *
 * <p>模型换成 {@link ScriptedChatModelClient}——四态收敛（完成 / 中断 / 失败 / 超时）必须
 * 能按剧本触发，否则最需要验证的那几条只能靠祈祷。
 *
 * <p>心跳与 flush 阈值都压到毫秒级：默认 15 秒心跳、2 秒 flush 会让这一组用例慢到没人愿意跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18092",
                "shizhi.model.provider=scripted",
                "shizhi.chat.heartbeat-interval=50ms",
                "shizhi.chat.flush-interval=10ms",
                "shizhi.chat.flush-min-chars=5",
                "shizhi.chat.emitter-timeout=30s"
        })
@ActiveProfiles("test")
@Import(ChatStreamIntegrationTest.ScriptedModelConfig.class)
class ChatStreamIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:18092";
    private static final long AWAIT_TIMEOUT_MILLIS = 5_000L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedChatModelClient model;

    @TestConfiguration
    static class ScriptedModelConfig {

        @Bean
        ScriptedChatModelClient scriptedChatModelClient() {
            return new ScriptedChatModelClient();
        }
    }

    private record Resp(int status, String body, String contentType) {
    }

    private record Account(String username, String token, long userId) {
    }

    private record SseEvent(String name, String data) {
    }

    @Test
    void streamsAckThenDeltasThenDoneAndPersistsEverything() throws Exception {
        Account account = newUser("ok");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.deltas("你好", "，", "世界"));

        List<SseEvent> events = readEvents(postStream(sessionId, "{\"content\":\"讲讲线程池\"}", account.token()));
        List<SseEvent> meaningful = withoutHeartbeats(events);

        assertThat(meaningful).extracting(SseEvent::name)
                .containsExactly("ack", "delta", "delta", "delta", "done");

        long userMessageId = longField(data(meaningful, "ack"), "userMessageId");
        long assistantMessageId = longField(data(meaningful, "ack"), "assistantMessageId");
        assertThat(intField(data(meaningful, "ack"), "streamState")).isZero();

        assertThat(meaningful.stream()
                .filter(event -> event.name().equals("delta"))
                .map(event -> stringField(event.data(), "text"))
                .toList()).containsExactly("你好", "，", "世界");

        String done = data(meaningful, "done");
        assertThat(longField(done, "messageId")).isEqualTo(assistantMessageId);
        assertThat(intField(done, "promptTokens")).isEqualTo(10);
        assertThat(intField(done, "completionTokens")).isEqualTo(20);
        assertThat(stringField(done, "finishReason")).isEqualTo("stop");
        assertThat(longField(done, "elapsedMs")).isGreaterThanOrEqualTo(0L);

        assertThat(messageContent(assistantMessageId)).isEqualTo("你好，世界");
        assertThat(messageState(assistantMessageId)).isEqualTo(1);
        assertThat(messageContent(userMessageId)).isEqualTo("讲讲线程池");

        // 一轮问答 = 两行消息，message_count +2；标题取首个问题的前 30 字
        assertThat(sessionMessageCount(sessionId)).isEqualTo(2);
        assertThat(sessionTitle(sessionId)).isEqualTo("讲讲线程池");
        assertThat(sessionLastActiveAt(sessionId)).isNotNull();

        assertThat(usageCount(account.userId())).isEqualTo(1);
        assertThat(successfulUsageCount(account.userId())).isEqualTo(1);
    }

    @Test
    void promptCarriesSystemIdentityAndRecentHistory() throws Exception {
        Account account = newUser("pmt");
        long sessionId = createSession(account.token());

        model.play(ScriptedChatModelClient.Script.deltas("答一"));
        readEvents(postStream(sessionId, "{\"content\":\"问题一\"}", account.token()));

        model.play(ScriptedChatModelClient.Script.deltas("答二"));
        readEvents(postStream(sessionId, "{\"content\":\"问题二\"}", account.token()));

        List<Turn> turns = model.lastTurns();
        // 第一段永远是系统身份，第二段起才是对话；本轮提问在最后（占位行因 stream_state=0 被排除）
        assertThat(turns.get(0).role()).isEqualTo("system");
        assertThat(turns).extracting(Turn::content)
                .containsSubsequence("问题一", "答一", "问题二");
        assertThat(turns).hasSize(4);
    }

    @Test
    void emptyContentIsRejectedAsJsonBeforeAnyStreamStarts() throws Exception {
        Account account = newUser("vld");
        long sessionId = createSession(account.token());
        // play() 同时把调用计数清零：这条用例要证明"模型根本没被调用"
        model.play(ScriptedChatModelClient.Script.deltas("不该被调用"));

        Resp resp = postJson("/api/sessions/" + sessionId + "/messages", "{\"content\":\"  \"}", account.token());

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.contentType()).contains("application/json");
        assertThat(resp.body()).contains("\"code\":1000").contains("\"field\":\"content\"");
        // 校验没过就不该留下任何消息行
        assertThat(messageCount(sessionId)).isZero();
        assertThat(model.calls()).isZero();
    }

    @Test
    void ownershipAndAuthFailuresAreJsonNotStream() throws Exception {
        Account owner = newUser("own");
        long sessionId = createSession(owner.token());

        Account stranger = newUser("str");
        Resp foreign = postJson("/api/sessions/" + sessionId + "/messages",
                "{\"content\":\"偷看\"}", stranger.token());
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.contentType()).contains("application/json");
        assertThat(foreign.body()).contains("\"code\":1004");

        Resp anonymous = postJson("/api/sessions/" + sessionId + "/messages", "{\"content\":\"hi\"}", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).contains("\"code\":1002");

        Resp missing = postJson("/api/sessions/999999999/messages", "{\"content\":\"hi\"}", owner.token());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":2004");
    }

    @Test
    void modelErrorAfterSomeDeltasInterruptsWithModelError() throws Exception {
        Account account = newUser("err");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "上游炸了"), "一半", "的回答"));

        List<SseEvent> meaningful = withoutHeartbeats(
                readEvents(postStream(sessionId, "{\"content\":\"提问\"}", account.token())));

        assertThat(meaningful).extracting(SseEvent::name).containsExactly("ack", "delta", "delta", "error");
        String error = data(meaningful, "error");
        assertThat(intField(error, "code")).isEqualTo(5001);

        long assistantMessageId = longField(error, "messageId");
        // 已经吐过的字要留在库里，否则用户刷新后连半截都看不到
        assertThat(messageContent(assistantMessageId)).isEqualTo("一半的回答");
        assertThat(messageState(assistantMessageId)).isEqualTo(2);
        assertThat(interruptReason(assistantMessageId)).isEqualTo("MODEL_ERROR");
        assertThat(successfulUsageCount(account.userId())).isZero();
    }

    @Test
    void modelErrorBeforeAnyDeltaMarksFailedWithEmptyContent() throws Exception {
        Account account = newUser("nul");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "首字之前就挂了")));

        List<SseEvent> meaningful = withoutHeartbeats(
                readEvents(postStream(sessionId, "{\"content\":\"提问\"}", account.token())));

        assertThat(meaningful).extracting(SseEvent::name).containsExactly("ack", "error");
        long assistantMessageId = longField(data(meaningful, "error"), "messageId");
        assertThat(messageState(assistantMessageId)).isEqualTo(3);
        assertThat(messageContent(assistantMessageId)).isEmpty();
        assertThat(interruptReason(assistantMessageId)).isEqualTo("MODEL_ERROR");
    }

    @Test
    void heartbeatIsPushedWhileWaitingForTheModel() throws Exception {
        Account account = newUser("hbk");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.firstDeltaAfter(300L, "慢回答"));

        List<SseEvent> events = readEvents(postStream(sessionId, "{\"content\":\"提问\"}", account.token()));

        assertThat(events).anyMatch(event -> event.name().equals("heartbeat"));
        assertThat(events).anyMatch(event -> event.name().equals("delta"));
        assertThat(events.get(events.size() - 1).name()).isEqualTo("done");
    }

    @Test
    void clientDisconnectInterruptsGenerationAndKeepsFlushedContent() throws Exception {
        Account account = newUser("cut");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.slowDeltas(120L,
                "0123456789", "0123456789", "0123456789", "0123456789",
                "0123456789", "0123456789", "0123456789", "0123456789"));

        long assistantMessageId;
        try (InputStream body = postStream(sessionId, "{\"content\":\"提问\"}", account.token()).body()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            assistantMessageId = readUntilTwoDeltasThenReadAck(reader);
        }

        awaitState(assistantMessageId, 2, "CLIENT_CLOSED");
        // 已 flush 的内容必须还在：丢失的上界只有一个 flush 周期
        assertThat(messageContent(assistantMessageId)).isNotEmpty().matches("[0-9]+");
    }

    @Test
    void modelTimeoutMarksServerTimeoutAndPushesError5003() throws Exception {
        Account account = newUser("tmo");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.failAfterDelay(
                new ModelTimeoutException("模型流空闲超过 300ms"), 300L));

        List<SseEvent> meaningful = withoutHeartbeats(
                readEvents(postStream(sessionId, "{\"content\":\"提问\"}", account.token())));

        assertThat(meaningful).extracting(SseEvent::name).containsExactly("ack", "error");
        String error = data(meaningful, "error");
        assertThat(intField(error, "code")).isEqualTo(5003);

        long assistantMessageId = longField(error, "messageId");
        assertThat(messageState(assistantMessageId)).isEqualTo(3);
        assertThat(interruptReason(assistantMessageId)).isEqualTo("SERVER_TIMEOUT");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 读到第二个 delta 就把连接关掉。返回值从 ack 事件里取——注意 ack 可能夹在心跳之间，
     * 所以先记下来，等连接关掉之后再返回。
     */
    private long readUntilTwoDeltasThenReadAck(BufferedReader reader) throws IOException {
        long assistantMessageId = -1L;
        int deltas = 0;
        String name = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null && deltas < 2) {
            if (line.isEmpty()) {
                if ("ack".equals(name)) {
                    assistantMessageId = longField(data.toString(), "assistantMessageId");
                } else if ("delta".equals(name)) {
                    deltas++;
                }
                name = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        assertThat(assistantMessageId).isPositive();
        return assistantMessageId;
    }

    private void awaitState(long messageId, int expectedState, String expectedReason) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        int state = -1;
        String reason = null;
        while (System.currentTimeMillis() < deadline) {
            state = messageState(messageId);
            reason = interruptReason(messageId);
            if (state == expectedState && Objects.equals(reason, expectedReason)) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待消息收敛时被中断", ex);
            }
        }
        throw new AssertionError("等待消息收敛超时：期望 state=" + expectedState + " reason=" + expectedReason
                + "，实际 state=" + state + " reason=" + reason);
    }

    private Account newUser(String prefix) throws Exception {
        String username = prefix + UUID.randomUUID().toString().substring(0, 8);
        Resp registered = postJson("/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"password123"}
                """.formatted(username, username), null);
        assertThat(registered.status()).isEqualTo(200);

        Resp login = postJson("/api/auth/login", """
                {"account":"%s","password":"password123"}
                """.formatted(username), null);
        String token = stringField(login.body(), "accessToken");
        assertThat(token).isNotBlank();

        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        assertThat(userId).isNotNull();
        return new Account(username, token, userId);
    }

    private long createSession(String token) throws Exception {
        Resp resp = postJson("/api/sessions", null, token);
        assertThat(resp.status()).isEqualTo(200);
        return longField(resp.body(), "id");
    }

    private Resp postJson(String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .POST(json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body(),
                response.headers().firstValue("Content-Type").orElse(""));
    }

    private HttpResponse<InputStream> postStream(long sessionId, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(BASE + "/api/sessions/" + sessionId + "/messages"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /** 按 SSE 规范切块：空行收尾，{@code event:} 给名字，{@code data:} 拼数据。 */
    private static List<SseEvent> readEvents(HttpResponse<InputStream> response) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (name != null) {
                        events.add(new SseEvent(name, data.toString()));
                        name = null;
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }
        if (name != null) {
            events.add(new SseEvent(name, data.toString()));
        }
        return events;
    }

    private static List<SseEvent> withoutHeartbeats(List<SseEvent> events) {
        return events.stream().filter(event -> !event.name().equals("heartbeat")).toList();
    }

    private static String data(List<SseEvent> events, String name) {
        return events.stream()
                .filter(event -> event.name().equals(name))
                .map(SseEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有收到 " + name + " 事件，实际：" + events));
    }

    private static String stringField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static long longField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(-?\\d+)").matcher(json);
        assertThat(matcher.find()).as("%s 应该是数字，实际：%s", field, json).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static int intField(String json, String field) {
        return (int) longField(json, field);
    }

    private String messageContent(long messageId) {
        return jdbc.queryForObject("SELECT content FROM chat_message WHERE id = ?", String.class, messageId);
    }

    private int messageState(long messageId) {
        Integer state = jdbc.queryForObject(
                "SELECT stream_state FROM chat_message WHERE id = ?", Integer.class, messageId);
        return state == null ? -1 : state;
    }

    private String interruptReason(long messageId) {
        return jdbc.queryForObject(
                "SELECT interrupt_reason FROM chat_message WHERE id = ?", String.class, messageId);
    }

    private int messageCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    private int sessionMessageCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT message_count FROM chat_session WHERE id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    private String sessionTitle(long sessionId) {
        return jdbc.queryForObject("SELECT title FROM chat_session WHERE id = ?", String.class, sessionId);
    }

    private Object sessionLastActiveAt(long sessionId) {
        return jdbc.queryForObject("SELECT last_active_at FROM chat_session WHERE id = ?", Object.class, sessionId);
    }

    private int usageCount(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ops_usage WHERE user_id = ? AND scene = 'CHAT'", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private int successfulUsageCount(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ops_usage WHERE user_id = ? AND scene = 'CHAT' AND success",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    // ==================================================================
    // 以下是 QA 补充的边界与竞态用例（严过关）。改这块之前先打个招呼，
    // 上面的 9 条由工程师维护。端口已占用 18092，这里不再单开上下文。
    // ==================================================================

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    @Qualifier(AsyncConfig.CHAT_SCHEDULER)
    private ScheduledExecutorService chatScheduler;

    /**
     * 100 段流式内容，落库结果必须<b>恰好等于</b>100 段拼接：不重一段、不漏一段。
     *
     * <p>这里的错法很隐蔽：周期 flush 已经写了一部分，收尾时如果 {@code markCompleted} 再把
     * {@code buffer.pendingText()} 追一遍，而 pending 没被 commit 清空，回答就会变成两遍；
     * 反过来只 commit 不追，末尾最后一段会丢。所以要同时断言长度和内容，不能只断言非空。
     */
    @Test
    void hundredSegmentsArePersistedExactlyOnce() throws Exception {
        Account account = newUser("dup");
        long sessionId = createSession(account.token());
        String[] segments = IntStream.range(0, 100).mapToObj(i -> "S%02d".formatted(i)).toArray(String[]::new);
        String expected = String.join("", segments);
        model.play(ScriptedChatModelClient.Script.deltas(segments));

        List<SseEvent> meaningful = withoutHeartbeats(
                readEvents(postStream(sessionId, "{\"content\":\"吐一百段\"}", account.token())));

        long assistantMessageId = longField(data(meaningful, "done"), "messageId");
        String stored = messageContent(assistantMessageId);
        assertThat(stored).as("落库内容必须与 100 段拼接完全一致").isEqualTo(expected);
        assertThat(stored).hasSize(300);
        // 顺序也要对：拼错顺序同样会让回答读起来是乱的
        assertThat(stored).startsWith("S00").endsWith("S99");
    }

    /**
     * 流还没结束的时候库里就应该已经有内容了——证明周期 flush 真的在工作，
     * 而不是「先攒在内存里、最后一次性写」。
     *
     * <p>攒到最后的写法在正常路径上看不出区别，但客户端一断开，用户就一个字都拿不到。
     */
    @Test
    void contentIsFlushedWhileTheStreamIsStillRunning() throws Exception {
        Account account = newUser("mid");
        long sessionId = createSession(account.token());
        String[] segments = IntStream.range(0, 20).mapToObj(i -> "F%02dAAA".formatted(i)).toArray(String[]::new);
        String expected = String.join("", segments);
        model.play(ScriptedChatModelClient.Script.slowDeltas(60L, segments));

        HttpResponse<InputStream> response = postStream(sessionId, "{\"content\":\"慢慢吐\"}", account.token());
        long assistantMessageId;
        int lengthMidStream;
        StringBuilder tail = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assistantMessageId = readAck(reader);
            readUntilDeltas(reader, 5, tail);
            lengthMidStream = messageContent(assistantMessageId).length();
            readToEnd(reader);
        }

        assertThat(lengthMidStream)
                .as("流进行到第 5 段时库里就该有内容（>0）且还不是最终值（<%d）", expected.length())
                .isGreaterThan(0)
                .isLessThan(expected.length());
        assertThat(messageContent(assistantMessageId)).isEqualTo(expected);
        assertThat(expected).hasSize(120);
    }

    /**
     * 心跳每 50ms 插一次，delta 必须仍然按原来的顺序拼回来，而且 done 之后不能再有任何事件。
     *
     * <p>四个写者共用一把锁（{@link SseSink}），锁一旦漏加，心跳就会插进 delta 中间，
     * 前端看到的是「半句 + 心跳 + 半句」；done 之后再来事件则说明心跳任务没停干净。
     */
    @Test
    void heartbeatDoesNotReorderDeltasNorEmitAfterDone() throws Exception {
        Account account = newUser("ord");
        long sessionId = createSession(account.token());
        String[] segments = IntStream.range(0, 30).mapToObj(i -> "D%02d".formatted(i)).toArray(String[]::new);
        model.play(ScriptedChatModelClient.Script.slowDeltas(30L, segments));

        List<SseEvent> events = readEvents(postStream(sessionId, "{\"content\":\"顺序\"}", account.token()));

        assertThat(events).anyMatch(event -> event.name().equals("heartbeat"));
        String joined = events.stream()
                .filter(event -> event.name().equals("delta"))
                .map(event -> stringField(event.data(), "text"))
                .collect(Collectors.joining());
        assertThat(joined).isEqualTo(String.join("", segments));

        int doneIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).name().equals("done")) {
                doneIndex = i;
            }
        }
        assertThat(doneIndex).as("必须收到 done").isGreaterThanOrEqualTo(0);
        assertThat(events.subList(doneIndex + 1, events.size()))
                .as("done 之后不该再有事件，实际：%s", events.subList(doneIndex + 1, events.size()))
                .isEmpty();
    }

    /**
     * 连续 5 次客户端断开：每条都要收敛成 2/CLIENT_CLOSED 且已 flush 内容还在，
     * 并且调度队列不能随断开次数单调增长——心跳任务泄漏的话，队列会一直涨到 OOM。
     *
     * <p>队列长度这里只断言「不会随次数增长」的弱条件（差值），因为
     * {@code ScheduledThreadPoolExecutor} 默认不开启 {@code removeOnCancelPolicy}，
     * 已 cancel 的任务会留在队列里直到原定执行时刻——这是可观测的现状，不是泄漏。
     */
    @Test
    void repeatedDisconnectsConvergeAndDoNotLeakHeartbeatTasks() throws Exception {
        Account account = newUser("leak");
        long sessionId = createSession(account.token());
        long queueAfterFirst = -1L;
        long queueAfterFifth = -1L;

        for (int round = 1; round <= 5; round++) {
            model.play(ScriptedChatModelClient.Script.slowDeltas(120L,
                    "0123456789", "0123456789", "0123456789", "0123456789",
                    "0123456789", "0123456789", "0123456789", "0123456789"));

            long assistantMessageId;
            try (InputStream body = postStream(sessionId, "{\"content\":\"第" + round + "次\"}", account.token()).body()) {
                assistantMessageId = readUntilTwoDeltasThenReadAck(new BufferedReader(
                        new InputStreamReader(body, StandardCharsets.UTF_8)));
            }

            awaitState(assistantMessageId, 2, "CLIENT_CLOSED");
            assertThat(messageContent(assistantMessageId)).isNotEmpty();
            // 每轮给心跳留出被 cancel 并出队的时间
            Thread.sleep(200L);
            long queued = schedulerQueueSize();
            if (round == 1) {
                queueAfterFirst = queued;
            }
            if (round == 5) {
                queueAfterFifth = queued;
            }
        }

        assertThat(queueAfterFifth)
                .as("第 5 次断开后的调度队列（第 1 次是 %d）不该明显增长", queueAfterFirst)
                .isLessThanOrEqualTo(queueAfterFirst + 2);
        assertThat(messageCount(sessionId)).isEqualTo(10);
    }

    /**
     * 已经被读时收敛成「中断」的行，不能被后续 flush 复活。
     *
     * <p>这正是「永久转圈」的来源：收敛把 state 写 2，flush 若不带 {@code stream_state = 0}
     * 条件，就会把内容和 updated_at 一起刷新，前端看到一条永远「生成中」的消息。
     */
    @Test
    void reconciledRowIsNotResurrectedByLateFlush() throws Exception {
        Account account = newUser("rev");
        long sessionId = createSession(account.token());
        long assistantMessageId = messageRepository.insertMessage(
                sessionId, account.userId(), "assistant", "", 0);

        assertThat(messageRepository.markInterrupted(assistantMessageId, null, "SHUTDOWN")).isEqualTo(1);
        assertThat(messageRepository.appendContent(assistantMessageId, "迟到的内容")).isZero();

        assertThat(messageState(assistantMessageId)).isEqualTo(2);
        assertThat(messageContent(assistantMessageId)).isEmpty();
        assertThat(interruptReason(assistantMessageId)).isEqualTo("SHUTDOWN");
    }

    /**
     * 同一会话问两次：{@code message_count} 是 4，且第二次不能改掉第一次定下的标题。
     *
     * <p>标题用 {@code COALESCE(title, ?)} 写入，语义是「只定一次」。如果写成直接赋值，
     * 用户问第二句时侧边栏标题会跟着变，看起来像换了个会话。
     */
    @Test
    void secondQuestionKeepsFirstTitleAndAddsTwoToCount() throws Exception {
        Account account = newUser("ttl");
        long sessionId = createSession(account.token());

        model.play(ScriptedChatModelClient.Script.deltas("答一"));
        readEvents(postStream(sessionId, "{\"content\":\"第一问\"}", account.token()));
        assertThat(sessionTitle(sessionId)).isEqualTo("第一问");
        assertThat(sessionMessageCount(sessionId)).isEqualTo(2);

        model.play(ScriptedChatModelClient.Script.deltas("答二"));
        readEvents(postStream(sessionId, "{\"content\":\"第二问\"}", account.token()));

        assertThat(sessionMessageCount(sessionId)).isEqualTo(4);
        assertThat(sessionTitle(sessionId)).as("标题只由首个问题决定").isEqualTo("第一问");
    }

    /**
     * 三种开流之前的失败都必须是普通 JSON，不能是 {@code text/event-stream}：
     * 一个已经建立的流是没法回 400/403/401 的，前端只会卡在「等待首屏」。
     */
    @Test
    void preStreamFailuresAreJsonNotEventStream() throws Exception {
        Account owner = newUser("cty");
        long sessionId = createSession(owner.token());
        Account stranger = newUser("ctz");
        // play() 同时把调用计数清零：这条用例要证明"失败路径根本没走到模型"
        model.play(ScriptedChatModelClient.Script.deltas("不该被调用"));

        Resp empty = postJson("/api/sessions/" + sessionId + "/messages", "{\"content\":\"   \"}", owner.token());
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.contentType()).as("content 为空的响应类型").contains("application/json")
                .doesNotContain("text/event-stream");
        assertThat(empty.body()).contains("\"code\":1000").contains("\"field\":\"content\"");

        Resp foreign = postJson("/api/sessions/" + sessionId + "/messages",
                "{\"content\":\"偷看\"}", stranger.token());
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.contentType()).as("别人的会话的响应类型").contains("application/json")
                .doesNotContain("text/event-stream");
        assertThat(foreign.body()).contains("\"code\":1004");

        Resp anonymous = postJson("/api/sessions/" + sessionId + "/messages", "{\"content\":\"hi\"}", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.contentType()).as("没带令牌的响应类型").contains("application/json")
                .doesNotContain("text/event-stream");
        assertThat(anonymous.body()).contains("\"code\":1002");

        assertThat(messageCount(sessionId)).as("三次失败都不该留下消息行").isZero();
        assertThat(model.calls()).as("三次失败都不该调用模型").isZero();
    }

    /**
     * 同一会话并发两条流：两边都要拿到完整的 done，内容互不串，两条 assistant 都是终态 1，
     * 且 {@code message_count} 恰好 +4。
     *
     * <p>假模型的剧本是单槽的，所以用 {@code enqueue} 按调用顺序分发两段不同的台词——
     * 「互不串」只有在两段台词不同的时候才能验出来。
     */
    @Test
    void concurrentStreamsDoNotMixContentOrLoseCounts() throws Exception {
        Account account = newUser("ccs");
        long sessionId = createSession(account.token());
        model.enqueue(ScriptedChatModelClient.Script.deltas("甲的回答"));
        model.enqueue(ScriptedChatModelClient.Script.deltas("乙的回答"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> storedContents = new ArrayList<>();
        try {
            List<Callable<List<SseEvent>>> tasks = List.of(
                    () -> readEvents(postStream(sessionId, "{\"content\":\"问题甲\"}", account.token())),
                    () -> readEvents(postStream(sessionId, "{\"content\":\"问题乙\"}", account.token())));
            for (Future<List<SseEvent>> future : pool.invokeAll(tasks)) {
                List<SseEvent> meaningful = withoutHeartbeats(future.get(15, TimeUnit.SECONDS));
                assertThat(meaningful).extracting(SseEvent::name).endsWith("done");
                long assistantId = longField(data(meaningful, "done"), "messageId");
                assertThat(messageState(assistantId)).isEqualTo(1);
                storedContents.add(messageContent(assistantId));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(storedContents).containsExactlyInAnyOrder("甲的回答", "乙的回答");
        assertThat(messageCount(sessionId)).isEqualTo(4);
        assertThat(sessionMessageCount(sessionId)).isEqualTo(4);
        assertThat(sessionTitle(sessionId)).isIn("问题甲", "问题乙");
    }

    /**
     * 上游在半截掐断（没有 {@code [DONE]}）时，消息<b>绝不能</b>被标成 1（完成）。
     *
     * <p>标成 1 的后果比报错严重得多：用户以后再也看不出这条回答是断的，沉淀链路还会
     * 把半截内容当成完整回答去做笔记。
     */
    @Test
    void truncatedUpstreamNeverMarksMessageCompleted() throws Exception {
        Account account = newUser("cut2");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "模型流在 [DONE] 之前中断"), "半截", "回答"));

        List<SseEvent> meaningful = withoutHeartbeats(
                readEvents(postStream(sessionId, "{\"content\":\"提问\"}", account.token())));

        assertThat(meaningful).extracting(SseEvent::name).containsExactly("ack", "delta", "delta", "error");
        long assistantMessageId = longField(data(meaningful, "error"), "messageId");
        assertThat(messageState(assistantMessageId)).as("半截流不能是完成态").isNotEqualTo(1);
        assertThat(messageState(assistantMessageId)).isEqualTo(2);
        assertThat(interruptReason(assistantMessageId)).isEqualTo("MODEL_ERROR");
        assertThat(messageContent(assistantMessageId)).isEqualTo("半截回答");
    }

    /**
     * 首字之前就失败的那次调用，{@code ops_usage.first_token_ms} 必须是 NULL，不能是 0 或负数。
     *
     * <p>0 和「还没到首字」是两件事：前者会被成本报表当成「首字瞬时到达」，
     * 把首字延迟的 P95 拉得好看得离谱，指标就废了。
     */
    @Test
    void usageRowHasNullFirstTokenWhenNothingWasEmitted() throws Exception {
        Account account = newUser("usg");
        long sessionId = createSession(account.token());
        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "首字之前就挂了")));

        List<SseEvent> meaningful = withoutHeartbeats(
                readEvents(postStream(sessionId, "{\"content\":\"提问\"}", account.token())));
        long assistantMessageId = longField(data(meaningful, "error"), "messageId");

        assertThat(messageState(assistantMessageId)).isEqualTo(3);
        assertThat(lastChatUsage(account.userId()))
                .contains("first_token_ms=NULL")
                .contains("success=false")
                .contains("finish_reason=MODEL_ERROR");
    }

    /** 最近一条 CHAT 用量行的关键字段，用来断言 first_token_ms 的空/非空。 */
    private String lastChatUsage(long userId) {
        return jdbc.queryForObject("""
                SELECT 'first_token_ms=' || COALESCE(first_token_ms::text, 'NULL')
                    || ' success=' || success
                    || ' finish_reason=' || COALESCE(finish_reason, 'NULL')
                FROM ops_usage WHERE user_id = ? AND scene = 'CHAT'
                ORDER BY id DESC LIMIT 1
                """, String.class, userId);
    }

    /**
     * 标题「取首个问题前 30 字」不能切在代理对中间。
     *
     * <p>29 个 ASCII + 1 个 emoji：按「字」（code point）算正好 30、不该截断；
     * 按 UTF-16 char 算长度是 31，{@code substring(0, 30)} 会把 emoji 劈成孤立高代理，
     * pgjdbc 编码 UTF-8 时替换成 {@code ?}，用户看到标题末尾一个乱码。
     */
    @Test
    void titleTruncationDoesNotSplitSurrogatePairs() throws Exception {
        Account account = newUser("emo");
        long sessionId = createSession(account.token());
        String question = "a".repeat(29) + "😀";
        assertThat(question.length()).isEqualTo(31);
        assertThat(question.codePointCount(0, question.length())).isEqualTo(30);
        model.play(ScriptedChatModelClient.Script.deltas("好"));

        Resp resp = postJson("/api/sessions/" + sessionId + "/messages",
                "{\"content\":\"" + question + "\"}", account.token());

        assertThat(resp.status()).isEqualTo(200);
        String title = sessionTitle(sessionId);
        assertThat(title)
                .as("30 个字符（含 emoji）不该被截断，更不该截出半个代理对；实际标题=%s", title)
                .isEqualTo(question);
        assertThat(title).doesNotContain("?");
        for (int i = 0; i < title.length(); i++) {
            if (Character.isHighSurrogate(title.charAt(i))) {
                assertThat(i + 1 < title.length() && Character.isLowSurrogate(title.charAt(i + 1)))
                        .as("位置 %d 的高代理后面必须跟低代理，否则标题里有半个 emoji", i)
                        .isTrue();
            }
        }

        // 真的需要截断的那一侧：40 个 emoji 应截成 30 个完整的 emoji（60 个 UTF-16 char），
        // 而不是 30 个 char（= 15 个 emoji）
        String longQuestion = "😀".repeat(40);
        Resp truncated = postJson("/api/sessions/" + sessionId + "/messages",
                "{\"content\":\"" + longQuestion + "\"}", account.token());
        assertThat(truncated.status()).isEqualTo(200);
        String truncatedTitle = sessionTitle(sessionId);
        // 标题只定一次，第二次不会改；所以这里单独建一个会话验截断
        long otherSession = createSession(account.token());
        assertThat(truncatedTitle).as("标题只由首个问题决定").isEqualTo(question);

        Resp second = postJson("/api/sessions/" + otherSession + "/messages",
                "{\"content\":\"" + longQuestion + "\"}", account.token());
        assertThat(second.status()).isEqualTo(200);
        String emojiTitle = sessionTitle(otherSession);
        assertThat(emojiTitle.codePointCount(0, emojiTitle.length()))
                .as("40 个 emoji 应截到 30 个「字」，实际=%s", emojiTitle)
                .isEqualTo(30);
        assertThat(emojiTitle).isEqualTo("😀".repeat(30));
    }

    /** 注入的调度器实际类型就是 {@link ScheduledThreadPoolExecutor}，取队列长度用。 */
    private long schedulerQueueSize() {
        if (chatScheduler instanceof ScheduledThreadPoolExecutor executor) {
            return executor.getQueue().size();
        }
        return -1L;
    }

    /** 只读到 ack 就返回，后面继续用同一个 reader 读，用来做「流中途查库」。 */
    private long readAck(BufferedReader reader) throws IOException {
        long assistantMessageId = -1L;
        String name = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if ("ack".equals(name)) {
                    assistantMessageId = longField(data.toString(), "assistantMessageId");
                    break;
                }
                name = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        assertThat(assistantMessageId).isPositive();
        return assistantMessageId;
    }

    /** 读到第 n 个 delta 就停下，把读到的事件名字顺手收集起来（内容不关心）。 */
    private void readUntilDeltas(BufferedReader reader, int n, StringBuilder sink) throws IOException {
        int deltas = 0;
        String name = null;
        String line;
        while ((line = reader.readLine()) != null && deltas < n) {
            if (line.isEmpty()) {
                if ("delta".equals(name)) {
                    deltas++;
                }
                name = null;
                continue;
            }
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                sink.append(line).append('\n');
            }
        }
        assertThat(deltas).as("流里至少有 %d 个 delta", n).isEqualTo(n);
    }

    /** 把剩下的流读完，丢弃内容（连接不读完会让服务端一直阻塞在写上）。 */
    private void readToEnd(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            // 读完即可
        }
    }
}
