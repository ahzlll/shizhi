package com.ahz.shizhi.model;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.ahz.shizhi.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 兼容接口的流式客户端（DeepSeek、vLLM、Ollama 的 /v1 都说得通）。
 *
 * <p>用 JDK 自带的 {@link HttpClient} 手写 SSE 解析，而不是引 WebClient / Spring AI：
 * 这一期要的只是"逐行读 data:"，依赖越少越不容易在版本升级时被拖住。
 *
 * <p><b>三层超时都靠一个看门狗线程</b>：首字 15s、空闲 60s、总时长 180s。超时的动作是
 * <b>关掉响应流</b>，让阻塞在 {@code readLine()} 的读线程自己抛 IOException 出来——
 * 这比给每个读操作单独设超时简单得多，也不需要能中断一个卡在 socket 上的线程。
 */
public class OpenAiCompatibleChatClient implements ChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String DATA_PREFIX = "data:";
    private static final String DONE_PAYLOAD = "[DONE]";
    private static final long WATCHDOG_TICK_MILLIS = 100L;
    private static final int CHARS_PER_TOKEN = 3;

    private final ModelProperties properties;
    private final ScheduledExecutorService watchdog;
    private final HttpClient http;

    public OpenAiCompatibleChatClient(ModelProperties properties, ScheduledExecutorService watchdog) {
        this.properties = properties;
        this.watchdog = watchdog;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.firstTokenTimeout())
                .build();
    }

    @Override
    public ModelResult stream(List<Turn> turns, Consumer<String> onDelta) {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // 06 验收里"故意配错 key 要看到明确错误"落在这里：
            // 启动不报错（否则本地和 CI 起不来），调用时报 5001 并把原因写进日志。
            log.error("模型未配置 api key，调用无法进行 provider={} baseUrl={}",
                    properties.provider(), properties.baseUrl());
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED,
                    "模型服务未配置 API key，请设置环境变量 SHIZHI_MODEL_API_KEY");
        }
        long startedAt = System.currentTimeMillis();
        HttpResponse<InputStream> response = send(turns, apiKey);
        return readStream(response, onDelta, startedAt, promptChars(turns));
    }

    @Override
    public String modelName() {
        return properties.name();
    }

    private HttpResponse<InputStream> send(List<Turn> turns, String apiKey) {
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                // 只覆盖到"响应头回来"为止，后面的读由看门狗管
                .timeout(properties.firstTokenTimeout().plusSeconds(5))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(turns), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 429) {
                closeQuietly(response.body());
                throw new ModelRateLimitException("模型服务限流（HTTP 429）");
            }
            if (status / 100 != 2) {
                closeQuietly(response.body());
                throw new ModelException(ErrorCode.MODEL_CALL_FAILED, "模型服务返回 HTTP " + status);
            }
            return response;
        } catch (HttpTimeoutException ex) {
            throw new ModelTimeoutException("请求模型超时（首字 " + properties.firstTokenTimeout().toMillis() + "ms）");
        } catch (IOException ex) {
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED, "调用模型失败：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED, "调用模型被中断", ex);
        }
    }

    private ModelResult readStream(HttpResponse<InputStream> response, Consumer<String> onDelta,
                                   long startedAt, int promptChars) {
        InputStream body = response.body();
        AtomicLong lastChunkAt = new AtomicLong(0L);
        AtomicBoolean firstContentSeen = new AtomicBoolean(false);
        AtomicReference<String> abortReason = new AtomicReference<>();

        ScheduledFuture<?> watchdogTask = watchdog.scheduleAtFixedRate(
                () -> checkDeadlines(body, startedAt, lastChunkAt, firstContentSeen, abortReason),
                WATCHDOG_TICK_MILLIS, WATCHDOG_TICK_MILLIS, TimeUnit.MILLISECONDS);

        int promptTokens = 0;
        int completionTokens = 0;
        int emittedChars = 0;
        boolean usageReported = false;
        boolean doneSeen = false;
        String finishReason = "stop";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (abortReason.get() != null) {
                    break;
                }
                String payload = payloadOf(line);
                if (payload == null) {
                    continue;
                }
                // 收到任意一块（哪怕 delta 是空串）都算"流还活着"，空闲计时据此重置
                lastChunkAt.set(System.currentTimeMillis());
                if (DONE_PAYLOAD.equals(payload)) {
                    doneSeen = true;
                    break;
                }
                JsonNode chunk = parse(payload);
                if (chunk == null) {
                    continue;
                }
                JsonNode usage = chunk.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    usageReported = true;
                }
                JsonNode choice = chunk.path("choices").path(0);
                JsonNode delta = choice.path("delta").path("content");
                if (delta.isTextual()) {
                    String text = delta.asText();
                    // 空串不回调：上游会用它做心跳/占位，前端收到空 delta 只会白渲染一帧
                    if (!text.isEmpty()) {
                        firstContentSeen.set(true);
                        emittedChars += text.length();
                        onDelta.accept(text);
                    }
                }
                JsonNode finish = choice.path("finish_reason");
                if (finish.isTextual()) {
                    finishReason = finish.asText();
                }
            }
        } catch (IOException ex) {
            if (abortReason.get() != null) {
                throw new ModelTimeoutException(abortReason.get());
            }
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED, "读取模型流失败：" + ex.getMessage(), ex);
        } finally {
            watchdogTask.cancel(false);
            closeQuietly(body);
        }

        if (abortReason.get() != null) {
            throw new ModelTimeoutException(abortReason.get());
        }
        if (!doneSeen) {
            // 没有 [DONE] 就 EOF 说明上游把连接掐了。半截内容绝不能按"正常结束"处理——
            // 那会让一条没说完的回答被标成 1，用户以后也看不出它是断的。
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED, "模型流在 [DONE] 之前中断");
        }
        if (!usageReported) {
            // 上游没回 usage 时只能估算。这时 ops_usage 的 usage_source 仍是 1，属于已知的乐观标注。
            promptTokens = estimateTokens(promptChars);
            completionTokens = estimateTokens(emittedChars);
        }
        return new ModelResult(promptTokens, completionTokens, finishReason);
    }

    private void checkDeadlines(InputStream body, long startedAt, AtomicLong lastChunkAt,
                                AtomicBoolean firstContentSeen, AtomicReference<String> abortReason) {
        long now = System.currentTimeMillis();
        long elapsed = now - startedAt;
        long firstTokenMillis = properties.firstTokenTimeout().toMillis();
        long idleMillis = properties.idleTimeout().toMillis();
        long totalMillis = properties.totalTimeout().toMillis();

        String reason = null;
        if (elapsed > totalMillis) {
            reason = "模型总时长超过 " + totalMillis + "ms";
        } else if (!firstContentSeen.get() && elapsed > firstTokenMillis) {
            reason = "模型首字超时（" + firstTokenMillis + "ms）";
        } else {
            long last = lastChunkAt.get();
            if (last > 0 && now - last > idleMillis) {
                reason = "模型流空闲超过 " + idleMillis + "ms";
            }
        }

        if (reason != null && abortReason.compareAndSet(null, reason)) {
            log.warn("{}，主动关闭上游连接 baseUrl={}", reason, properties.baseUrl());
            closeQuietly(body);
        }
    }

    private URI endpoint() {
        String base = properties.baseUrl();
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/chat/completions");
    }

    private String requestBody(List<Turn> turns) {
        List<Map<String, String>> messages = new ArrayList<>(turns.size());
        for (Turn turn : turns) {
            messages.add(Map.of(
                    "role", turn.role(),
                    "content", turn.content() == null ? "" : turn.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.name());
        body.put("messages", messages);
        body.put("stream", true);
        // 不带 include_usage 上游不会回 token 数，成本统计就只能全走估算
        body.put("stream_options", Map.of("include_usage", true));
        return JSON.writeValueAsString(body);
    }

    /** 只认 {@code data:} 行；空行、{@code event:}、{@code : keep-alive} 注释一律跳过。 */
    private static String payloadOf(String line) {
        if (!line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String payload = line.substring(DATA_PREFIX.length()).trim();
        return payload.isEmpty() ? null : payload;
    }

    /** 单个数据块坏掉不该毁掉整段回答，记一条日志继续读。 */
    private static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JacksonException ex) {
            log.warn("跳过无法解析的 SSE 数据块：{}", payload);
            return null;
        }
    }

    private static int promptChars(List<Turn> turns) {
        int chars = 0;
        for (Turn turn : turns) {
            chars += turn.content() == null ? 0 : turn.content().length();
        }
        return chars;
    }

    private static int estimateTokens(int chars) {
        return chars <= 0 ? 0 : Math.max(1, (int) Math.ceil(chars / (double) CHARS_PER_TOKEN));
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ex) {
            log.debug("关闭模型响应流失败", ex);
        }
    }
}
