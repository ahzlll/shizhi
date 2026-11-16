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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ahz.shizhi.common.api.ErrorCode;
import com.ahz.shizhi.model.ModelException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 重试被中断 / 失败回答的端到端测试。
 *
 * <p>重试是一条会改两条记录的写操作：新行要建、旧行要标记为被替代、失败时还要把标记撤回去。
 * 这些都不是单看 HTTP 状态码能验的，所以每条用例都回来查库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shizhi.model.provider=scripted",
                "shizhi.chat.heartbeat-interval=50ms",
                "shizhi.chat.flush-interval=10ms",
                "shizhi.chat.flush-min-chars=5",
                "shizhi.chat.emitter-timeout=30s"
        })
@ActiveProfiles("test")
@Import(MessageRegenerateIntegrationTest.ScriptedModelConfig.class)
class MessageRegenerateIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

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

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    @Test
    void retriesInterruptedAnswerAndHidesTheOldOne() throws Exception {
        Account account = newUser("rt");
        long sessionId = createSession(account.token());
        long oldMessageId = interruptWithContent(sessionId, account.token(), "原始提问", "一半", "的回答");
        long originalUserMessageId = lastUserMessageId(sessionId);

        model.play(ScriptedChatModelClient.Script.deltas("重试后的回答"));
        List<SseEvent> events = withoutHeartbeats(regenerateStream(oldMessageId, account.token()));

        assertThat(names(events)).containsExactly("ack", "delta", "done");
        String ack = data(events, "ack");
        // 前端靠 ack 里的 userMessageId 把新气泡挂回原来的提问
        assertThat(longField(ack, "userMessageId")).isEqualTo(originalUserMessageId);
        long newMessageId = longField(ack, "assistantMessageId");
        assertThat(newMessageId).isNotEqualTo(oldMessageId);
        assertThat(longField(data(events, "done"), "messageId")).isEqualTo(newMessageId);

        assertThat(supersededBy(oldMessageId)).isEqualTo(newMessageId);
        assertThat(messageState(newMessageId)).isEqualTo(1);
        assertThat(messageContent(newMessageId)).isEqualTo("重试后的回答");
        // 一轮问答 +2，重试只多一条回答 +1
        assertThat(sessionMessageCount(sessionId)).isEqualTo(3);

        List<Long> history = messageIds(get("/api/sessions/" + sessionId + "/messages", account.token()).body());
        assertThat(history).contains(newMessageId).doesNotContain(oldMessageId);
    }

    @Test
    void retriesFailedAnswerThatNeverProducedAnyText() throws Exception {
        Account account = newUser("fl");
        long sessionId = createSession(account.token());
        long failedMessageId = failWithoutContent(sessionId, account.token(), "会失败的问题");
        assertThat(messageState(failedMessageId)).isEqualTo(3);

        model.play(ScriptedChatModelClient.Script.deltas("终于成功了"));
        List<SseEvent> events = withoutHeartbeats(regenerateStream(failedMessageId, account.token()));

        assertThat(names(events)).containsExactly("ack", "delta", "done");
        assertThat(supersededBy(failedMessageId)).isEqualTo(longField(data(events, "ack"), "assistantMessageId"));
    }

    @Test
    void rejectsMessagesThatCannotBeRetried() throws Exception {
        Account owner = newUser("rj");
        long sessionId = createSession(owner.token());

        model.play(ScriptedChatModelClient.Script.deltas("完整回答"));
        List<SseEvent> completed = withoutHeartbeats(sendMessage(sessionId, "正常提问", owner.token()));
        long completedId = longField(data(completed, "ack"), "assistantMessageId");
        long userMessageId = longField(data(completed, "ack"), "userMessageId");

        // 已经完成的回答
        assertRetryRejected(completedId, owner.token(), 409, ErrorCode.MESSAGE_ALREADY_COMPLETED.code());
        // 用户自己发的消息
        assertRetryRejected(userMessageId, owner.token(), 409, ErrorCode.MESSAGE_ALREADY_COMPLETED.code());
        // 还在生成中的占位行
        long generatingId = insertGeneratingMessage(sessionId, owner.userId());
        assertRetryRejected(generatingId, owner.token(), 409, ErrorCode.MESSAGE_ALREADY_COMPLETED.code());
        // 不存在的消息
        assertRetryRejected(999_999_999L, owner.token(), 404, ErrorCode.RESOURCE_NOT_FOUND.code());
        // 别人的消息
        Account stranger = newUser("st");
        assertRetryRejected(completedId, stranger.token(), 403, ErrorCode.FORBIDDEN.code());
    }

    @Test
    void refusesTheFourthGenerationInARetryChain() throws Exception {
        Account account = newUser("ch");
        long sessionId = createSession(account.token());

        long first = interruptWithContent(sessionId, account.token(), "第一问", "第一代");
        long second = retryAndInterrupt(first, account.token(), "第二代");
        long third = retryAndInterrupt(second, account.token(), "第三代");

        // 原回答、第二代、第三代各算一代，再重试就是第四代
        Resp rejected = regenerateJson(third, account.token());
        assertThat(rejected.status()).isEqualTo(429);
        assertThat(rejected.body()).contains("\"code\":" + ErrorCode.RETRY_LIMIT_EXCEEDED.code());
        assertThat(rejected.contentType()).contains("application/json");
    }

    @Test
    void concurrentDoubleClickLeavesExactlyOneNewRow() throws Exception {
        Account account = newUser("cc");
        long sessionId = createSession(account.token());
        long oldMessageId = interruptWithContent(sessionId, account.token(), "并发提问", "原回答");
        long rowsBefore = assistantRowCount(sessionId);

        model.play(ScriptedChatModelClient.Script.deltas("并发重试出来的回答"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Resp> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    start.await();
                    synchronized (results) {
                        results.add(regenerateText(oldMessageId, account.token()));
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).extracting(Resp::status).containsExactlyInAnyOrder(200, 409);
        Resp rejected = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertThat(rejected.body()).contains("\"code\":" + ErrorCode.MESSAGE_ALREADY_COMPLETED.code());

        // 只多出一条新行：落败的那次请求在事务里回滚了
        assertThat(assistantRowCount(sessionId)).isEqualTo(rowsBefore + 1);
        assertThat(supersededBy(oldMessageId)).isNotNull();
    }

    @Test
    void restoresTheOldAnswerWhenRetryProducesNothing() throws Exception {
        Account account = newUser("rs");
        long sessionId = createSession(account.token());
        long oldMessageId = interruptWithContent(sessionId, account.token(), "会失败的重试", "半截回答");
        assertThat(supersededBy(oldMessageId)).isNull();

        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "上游又炸了")));
        List<SseEvent> events = withoutHeartbeats(regenerateStream(oldMessageId, account.token()));

        long newMessageId = longField(data(events, "error"), "messageId");
        assertThat(messageState(newMessageId)).isEqualTo(3);
        // 新行什么都没产出，旧行的「已被替代」要撤回，半截回答重新可见
        assertThat(supersededBy(oldMessageId)).isNull();

        List<Long> history = messageIds(get("/api/sessions/" + sessionId + "/messages", account.token()).body());
        // 旧回答重新可见；那次没答出来的行不进历史，否则每失败一次就多一条空气泡
        assertThat(history).contains(oldMessageId).doesNotContain(newMessageId);
    }

    // ------------------------------------------------------------------
    // 场景构造
    // ------------------------------------------------------------------

    /** 造一条「吐了两段之后出错」的回答，落在状态 2（中断）。 */
    private long interruptWithContent(long sessionId, String token, String question, String... deltas) throws Exception {
        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "上游炸了"), deltas));
        List<SseEvent> events = withoutHeartbeats(sendMessage(sessionId, question, token));
        long messageId = longField(data(events, "error"), "messageId");
        assertThat(messageState(messageId)).isEqualTo(2);
        return messageId;
    }

    /** 造一条「一个字都没出就失败」的回答，落在状态 3。 */
    private long failWithoutContent(long sessionId, String token, String question) throws Exception {
        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "首字之前就挂了")));
        List<SseEvent> events = withoutHeartbeats(sendMessage(sessionId, question, token));
        return longField(data(events, "error"), "messageId");
    }

    /** 重试一次并让它也停在中断态，用于把重试链加长。 */
    private long retryAndInterrupt(long messageId, String token, String... deltas) throws Exception {
        model.play(ScriptedChatModelClient.Script.deltasThenFail(
                new ModelException(ErrorCode.MODEL_CALL_FAILED, "上游炸了"), deltas));
        List<SseEvent> events = withoutHeartbeats(regenerateStream(messageId, token));
        long newMessageId = longField(data(events, "error"), "messageId");
        assertThat(messageState(newMessageId)).isEqualTo(2);
        return newMessageId;
    }

    private long insertGeneratingMessage(long sessionId, long userId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, user_id, role, content, stream_state, updated_at)
                VALUES (?, ?, 'assistant', '', 0, now()) RETURNING id
                """, Long.class, sessionId, userId);
        assertThat(id).isNotNull();
        return id;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private Account newUser(String prefix) throws Exception {
        String username = prefix + UUID.randomUUID().toString().substring(0, 8);
        assertThat(postJson("/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"password123"}
                """.formatted(username, username), null).status()).isEqualTo(200);
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

    private List<SseEvent> sendMessage(long sessionId, String content, String token) throws Exception {
        HttpResponse<InputStream> response = postStream(
                "/api/sessions/" + sessionId + "/messages", "{\"content\":\"" + content + "\"}", token);
        return readEvents(response);
    }

    private List<SseEvent> regenerateStream(long messageId, String token) throws Exception {
        HttpResponse<InputStream> response = postStream("/api/messages/" + messageId + "/regenerate", null, token);
        assertThat(response.statusCode()).isEqualTo(200);
        return readEvents(response);
    }

    private Resp regenerateJson(long messageId, String token) throws Exception {
        return postJson("/api/messages/" + messageId + "/regenerate", null, token);
    }

    /** 并发用例用：把整段 SSE 当字符串读完，只关心状态码与是否跑完。 */
    private Resp regenerateText(long messageId, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(base() + "/api/messages/" + messageId + "/regenerate"))
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body(),
                response.headers().firstValue("Content-Type").orElse(""));
    }

    private void assertRetryRejected(long messageId, String token, int status, int code) throws Exception {
        Resp resp = regenerateJson(messageId, token);
        assertThat(resp.status()).isEqualTo(status);
        assertThat(resp.body()).contains("\"code\":" + code);
        assertThat(resp.contentType()).contains("application/json");
    }

    private Resp postJson(String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
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

    private HttpResponse<InputStream> postStream(String path, String json, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Accept", "text/event-stream")
                .POST(json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.header("Authorization", "Bearer " + token);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private Resp get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body(),
                response.headers().firstValue("Content-Type").orElse(""));
    }

    // ------------------------------------------------------------------
    // SSE 与 JSON 解析
    // ------------------------------------------------------------------

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

    private static List<String> names(List<SseEvent> events) {
        return events.stream().map(SseEvent::name).toList();
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

    private static List<Long> messageIds(String body) {
        List<Long> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{\"id\":(\\d+),\"role\":\"").matcher(body);
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 查库
    // ------------------------------------------------------------------

    private int messageState(long messageId) {
        Integer state = jdbc.queryForObject(
                "SELECT stream_state FROM chat_message WHERE id = ?", Integer.class, messageId);
        return state == null ? -1 : state;
    }

    private String messageContent(long messageId) {
        return jdbc.queryForObject("SELECT content FROM chat_message WHERE id = ?", String.class, messageId);
    }

    private Long supersededBy(long messageId) {
        return jdbc.queryForObject(
                "SELECT superseded_by FROM chat_message WHERE id = ?", Long.class, messageId);
    }

    private int sessionMessageCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT message_count FROM chat_session WHERE id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    private long assistantRowCount(long sessionId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE session_id = ? AND role = 'assistant'",
                Long.class, sessionId);
        return count == null ? 0L : count;
    }

    private long lastUserMessageId(long sessionId) {
        Long id = jdbc.queryForObject(
                "SELECT max(id) FROM chat_message WHERE session_id = ? AND role = 'user'",
                Long.class, sessionId);
        return id == null ? 0L : id;
    }
}
