package com.ahz.shizhi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.ahz.shizhi.chat.ClientClosedException;
import com.ahz.shizhi.common.api.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 用 JDK 自带的 {@link HttpServer} 起一个假上游，喂预置的 SSE 文本。
 *
 * <p>不引 WireMock 之类的依赖：要测的是"逐行解析 data:"和三层超时，一个能返回固定字节的
 * HTTP 服务器就够了。
 */
class OpenAiCompatibleChatClientTest {

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "model-watchdog-test");
        thread.setDaemon(true);
        return thread;
    });

    private static final String CHAT_PATH = "/chat/completions";

    private HttpServer server;
    private String lastRequestBody;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @AfterAll
    static void stopScheduler() {
        SCHEDULER.shutdownNow();
    }

    @Test
    void concatenatesDeltasSkipsEmptyOnesAndReadsUsage() {
        String body = """
                data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"好"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":""},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":34}}

                data: [DONE]

                """;
        OpenAiCompatibleChatClient client = clientWith(body, 200, Duration.ofSeconds(5), Duration.ofSeconds(5));

        List<String> deltas = new ArrayList<>();
        ModelResult result = client.stream(
                List.of(Turn.system("系统"), Turn.user("问题")), deltas::add);

        assertThat(deltas).containsExactly("你", "好");
        assertThat(result.promptTokens()).isEqualTo(12);
        assertThat(result.completionTokens()).isEqualTo(34);
        assertThat(result.finishReason()).isEqualTo("stop");

        // 上游必须拿到 stream:true 和 include_usage，否则既不是流式、也没有 token 数
        assertThat(lastRequestBody)
                .contains("\"stream\":true")
                .contains("\"include_usage\":true")
                .contains("\"role\":\"system\"")
                .contains("\"content\":\"问题\"");
    }

    @Test
    void rateLimitBecomesRateLimitException() {
        OpenAiCompatibleChatClient client = clientWith("", 429, Duration.ofSeconds(5), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.stream(List.of(Turn.user("hi")), delta -> {
        }))
                .isInstanceOf(ModelRateLimitException.class)
                .extracting(ex -> ((ModelException) ex).errorCode())
                .isEqualTo(ErrorCode.MODEL_RATE_LIMITED);
    }

    @Test
    void otherNonSuccessStatusBecomesModelCallFailed() {
        OpenAiCompatibleChatClient client = clientWith("upstream boom", 500, Duration.ofSeconds(5), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.stream(List.of(Turn.user("hi")), delta -> {
        }))
                .isInstanceOf(ModelException.class)
                .extracting(ex -> ((ModelException) ex).errorCode())
                .isEqualTo(ErrorCode.MODEL_CALL_FAILED);
    }

    @Test
    void truncatedStreamWithoutDoneIsTreatedAsFailure() {
        // 上游把连接掐了：有内容但没 [DONE]，绝不能当正常结束
        String body = """
                data: {"choices":[{"delta":{"content":"半截"}}]}

                """;
        OpenAiCompatibleChatClient client = clientWith(body, 200, Duration.ofSeconds(5), Duration.ofSeconds(5));

        List<String> deltas = new ArrayList<>();
        assertThatThrownBy(() -> client.stream(List.of(Turn.user("hi")), deltas::add))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("[DONE]");
        assertThat(deltas).containsExactly("半截");
    }

    @Test
    void idlenessBeyondIdleTimeoutBecomesTimeoutException() {
        OpenAiCompatibleChatClient client = clientStallingAfterFirstChunk(Duration.ofMillis(300));

        List<String> deltas = new ArrayList<>();
        assertThatThrownBy(() -> client.stream(List.of(Turn.user("hi")), deltas::add))
                .isInstanceOf(ModelTimeoutException.class)
                .extracting(ex -> ((ModelException) ex).errorCode())
                .isEqualTo(ErrorCode.MODEL_TIMEOUT);
        assertThat(deltas).containsExactly("卡");
    }

    /**
     * 消费者（流式对话那一层）在第一段就喊停：异常必须原样穿出去，不能被包成 ModelException。
     *
     * <p>混成 ModelException 的后果是断线被记成 5001、{@code interrupt_reason} 写成 MODEL_ERROR——
     * 用户关掉页面，服务端却以为模型坏了。
     *
     * <p>同时钉住「上游连接真的被关掉」：假上游用 chunked 一直往下写，写失败（IOException）
     * 就是客户端断了连接的观测点。这一条才是「停止烧 token」的证据——只断言回调次数不够，
     * 回调停了但连接还在读，上游照样在计费。
     */
    @Test
    void consumerAbortPropagatesUntouchedAndClosesUpstream() {
        AtomicBoolean upstreamClosed = new AtomicBoolean(false);
        String baseUrl = startServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // 长度未知（chunked）：假上游可以一直往下写，直到写失败为止
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"一段\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] next = "data: {\"choices\":[{\"delta\":{\"content\":\"二段\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    out.write(next);
                    out.flush();
                } catch (IOException ex) {
                    upstreamClosed.set(true);
                    return;
                }
                Thread.sleep(20L);
            }
        });
        ModelProperties properties = new ModelProperties("deepseek", baseUrl, "test-key", "test-model",
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(20));
        OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(properties, SCHEDULER);

        ClientClosedException abort = new ClientClosedException();
        AtomicInteger delivered = new AtomicInteger();

        assertThatThrownBy(() -> client.stream(List.of(Turn.user("hi")), delta -> {
            delivered.incrementAndGet();
            throw abort;
        }))
                .isSameAs(abort)
                .isNotInstanceOf(ModelException.class)
                .isNotInstanceOf(ModelTimeoutException.class);

        // 第二段没有被投递：读流循环在回调抛异常后立刻停了
        assertThat(delivered).hasValue(1);
        assertThat(awaitTrue(upstreamClosed, 3_000L))
                .as("上游连接必须被关掉，否则那个没人看的花费还在继续")
                .isTrue();
    }

    /** 等服务端观察到「写失败」。用轮询而不是睡固定时长，避免测试既慢又不稳。 */
    private static boolean awaitTrue(AtomicBoolean flag, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (flag.get()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return flag.get();
            }
        }
        return flag.get();
    }

    @Test
    void missingApiKeyFailsLoudlyInsteadOfReturningEmptyAnswer() {
        ModelProperties properties = new ModelProperties("deepseek", "http://127.0.0.1:1", "  ",
                "deepseek-chat", Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(properties, SCHEDULER);

        assertThatThrownBy(() -> client.stream(List.of(Turn.user("hi")), delta -> {
        }))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("API key")
                .extracting(ex -> ((ModelException) ex).errorCode())
                .isEqualTo(ErrorCode.MODEL_CALL_FAILED);
    }

    private OpenAiCompatibleChatClient clientWith(String payload, int status,
                                                 Duration idle, Duration total) {
        String baseUrl = startServer(exchange -> {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
            }
        });
        ModelProperties properties = new ModelProperties("deepseek", baseUrl, "test-key", "test-model",
                Duration.ofSeconds(5), idle, total);
        return new OpenAiCompatibleChatClient(properties, SCHEDULER);
    }

    /**
     * 声明 1KB 的响应体，只写一个 chunk 就卡住——模拟"上游 TCP 半开"。
     * 看门狗应当关掉流，让读线程抛出来并转成超时。
     */
    private OpenAiCompatibleChatClient clientStallingAfterFirstChunk(Duration idle) {
        String baseUrl = startServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 1024);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"卡\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(2_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        ModelProperties properties = new ModelProperties("deepseek", baseUrl, "test-key", "test-model",
                Duration.ofSeconds(5), idle, Duration.ofSeconds(10));
        return new OpenAiCompatibleChatClient(properties, SCHEDULER);
    }

    private String startServer(ExchangeHandler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(CHAT_PATH, exchange -> {
                lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    handler.handle(exchange);
                } catch (Exception ex) {
                    throw new IllegalStateException("假上游处理请求失败", ex);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (Exception ex) {
            throw new IllegalStateException("启动假上游失败", ex);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
