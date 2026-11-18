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
import java.util.concurrent.atomic.AtomicInteger;
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
 * OpenAI 兼容接口的流式客户端。
 *
 * <p>用 JDK 自带的 {@link HttpClient} 手写 SSE 解析，而不是引 WebClient / Spring AI：
 * 这里要的只是「逐行读 data:」，依赖越少越不容易在版本升级时被拖住。
 *
 * <p><b>三层超时都靠一个看门狗线程</b>：首字、空闲、总时长。超时的动作是<b>关掉响应流</b>，
 * 让阻塞在 {@code readLine()} 的读线程自己抛 IOException 出来——这比给每个读操作单独设超时
 * 简单得多，也不需要能中断一个卡在 socket 上的线程。
 *
 * <p>首字的判定是「<b>一个数据块都没收到</b>」，不是「没收到正文」：思考型模型会先吐十几秒的
 * reasoning 块，那段时间连接是活的，按正文算首字会把它们误杀。
 */
public class OpenAiCompatibleChatClient implements ChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String DATA_PREFIX = "data:";
    private static final String DONE_PAYLOAD = "[DONE]";
    private static final String EVENT_STREAM_CONTENT_TYPE = "text/event-stream";

    /** 上游用错误码表达限流，而不是用 HTTP 429。 */
    private static final String RATE_LIMIT_CODE = "1305";

    private static final long WATCHDOG_TICK_MILLIS = 100L;
    private static final int CHARS_PER_TOKEN = 3;
    private static final int ERROR_BODY_PREVIEW_LENGTH = 200;

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

    /**
     * 调主模型；主模型失败且这一轮还没吐出任何正文时，换备用模型再试一次。
     *
     * <p>两个不降级的例外：<b>超时</b>（换模型只会让用户多等一倍）和<b>已经吐过字</b>
     * （换模型从头生成会让回答出现重复的半截）。
     */
    @Override
    public ModelResult stream(List<Turn> turns, Consumer<String> onDelta) {
        String apiKey = requireApiKey();
        int promptChars = promptChars(turns);
        AtomicInteger emittedChars = new AtomicInteger();
        Consumer<String> counting = delta -> {
            emittedChars.addAndGet(delta.length());
            onDelta.accept(delta);
        };

        try {
            return attempt(properties.name(), turns, counting, promptChars, apiKey);
        } catch (ModelTimeoutException ex) {
            throw ex;
        } catch (ModelException ex) {
            String fallback = properties.fallbackName();
            if (fallback == null || fallback.isBlank() || emittedChars.get() > 0) {
                throw ex;
            }
            log.warn("主模型 {} 失败（{}），改用备用模型 {} 重试",
                    properties.name(), ex.getMessage(), fallback);
            return attempt(fallback, turns, counting, promptChars, apiKey);
        }
    }

    @Override
    public String modelName() {
        return properties.name();
    }

    private ModelResult attempt(String model, List<Turn> turns, Consumer<String> onDelta,
                                int promptChars, String apiKey) {
        long startedAt = System.currentTimeMillis();
        HttpResponse<InputStream> response = send(model, turns, apiKey);
        return readStream(model, response, onDelta, startedAt, promptChars);
    }

    private String requireApiKey() {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // 配错 key 不能死在启动上——本地和测试环境会直接起不来。
            // 放到调用时失败，报 5001，把原因写进日志。
            log.error("模型未配置 api key，调用无法进行 provider={} baseUrl={}",
                    properties.provider(), properties.baseUrl());
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED,
                    "模型服务未配置 API key，请设置环境变量 SHIZHI_MODEL_API_KEY");
        }
        return apiKey;
    }

    private HttpResponse<InputStream> send(String model, List<Turn> turns, String apiKey) {
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                // 只覆盖到「响应头回来」为止，后面的读由看门狗管
                .timeout(properties.firstTokenTimeout().plusSeconds(5))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", EVENT_STREAM_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, turns), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 429) {
                closeQuietly(response.body());
                throw new ModelRateLimitException("模型服务限流（HTTP 429）");
            }
            if (status / 100 != 2) {
                throw upstreamFailure(response, "模型服务返回 HTTP " + status);
            }
            ensureEventStream(response);
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

    /**
     * 限流时上游仍然回 HTTP 200，只是 Content-Type 是 JSON、body 里放错误对象。
     * 不先看类型的话，这个响应会一路走到「没有 [DONE]」，被报成「模型流中断」——语义完全错了。
     */
    private void ensureEventStream(HttpResponse<InputStream> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains(EVENT_STREAM_CONTENT_TYPE)) {
            return;
        }
        throw upstreamFailure(response, "模型服务返回了非事件流响应");
    }

    /** 把上游错误体翻译成限流异常或调用失败，带上它自己的 message 方便排查。 */
    private ModelException upstreamFailure(HttpResponse<InputStream> response, String summary) {
        String body = readBody(response.body());
        JsonNode error = parseQuietly(body);
        String code = error == null ? "" : error.path("error").path("code").asText("");
        String message = error == null ? "" : error.path("error").path("message").asText("");
        log.warn("{}，上游错误 code={} message={}", summary, code, message);

        String detail = message.isBlank() ? abbreviate(body) : message;
        if (RATE_LIMIT_CODE.equals(code)) {
            return new ModelRateLimitException("模型服务限流：" + detail);
        }
        return new ModelException(ErrorCode.MODEL_CALL_FAILED, summary + "：" + detail);
    }

    private ModelResult readStream(String model, HttpResponse<InputStream> response, Consumer<String> onDelta,
                                   long startedAt, int promptChars) {
        InputStream body = response.body();
        AtomicLong lastChunkAt = new AtomicLong(0L);
        AtomicReference<String> abortReason = new AtomicReference<>();

        ScheduledFuture<?> watchdogTask = watchdog.scheduleAtFixedRate(
                () -> checkDeadlines(body, startedAt, lastChunkAt, abortReason),
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
                // 收到任意一块（正文、空 delta、思考块）都算「流还活着」，空闲计时据此重置
                lastChunkAt.set(System.currentTimeMillis());
                if (DONE_PAYLOAD.equals(payload)) {
                    doneSeen = true;
                    break;
                }
                JsonNode chunk = parseQuietly(payload);
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
                    // 空串不回调：上游会用它做心跳/占位，前端收到空 delta 只会白渲染一帧。
                    // reasoning_content（思考过程）也不透传：它不是答案的一部分。
                    if (!text.isEmpty()) {
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
            // 没有 [DONE] 就 EOF 说明上游把连接掐了。半截内容绝不能按「正常结束」处理——
            // 那会让一条没说完的回答被标成完成，用户以后也看不出它是断的。
            throw new ModelException(ErrorCode.MODEL_CALL_FAILED, "模型流在 [DONE] 之前中断");
        }
        if (!usageReported) {
            // 上游没回 usage 时只能估算。这时用量来源仍记为供应商返回，属于已知的乐观标注。
            promptTokens = estimateTokens(promptChars);
            completionTokens = estimateTokens(emittedChars);
        }
        return new ModelResult(promptTokens, completionTokens, finishReason, model);
    }

    private void checkDeadlines(InputStream body, long startedAt, AtomicLong lastChunkAt,
                                AtomicReference<String> abortReason) {
        long now = System.currentTimeMillis();
        long elapsed = now - startedAt;
        long firstTokenMillis = properties.firstTokenTimeout().toMillis();
        long idleMillis = properties.idleTimeout().toMillis();
        long totalMillis = properties.totalTimeout().toMillis();

        String reason = null;
        if (elapsed > totalMillis) {
            reason = "模型总时长超过 " + totalMillis + "ms";
        } else if (lastChunkAt.get() == 0 && elapsed > firstTokenMillis) {
            // 按「一块都没收到」判，不按「没收到正文」判：思考型模型先吐 reasoning 块，
            // 那段时间一个字正文都没有，但连接是活的，按正文判会把它们全都误杀。
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

    private String requestBody(String model, List<Turn> turns) {
        List<Map<String, String>> messages = new ArrayList<>(turns.size());
        for (Turn turn : turns) {
            messages.add(Map.of(
                    "role", turn.role(),
                    "content", turn.content() == null ? "" : turn.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
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
    private static JsonNode parseQuietly(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JacksonException ex) {
            log.warn("跳过无法解析的 JSON：{}", payload);
            return null;
        }
    }

    private static String readBody(InputStream body) {
        try (InputStream stream = body) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("读取模型错误响应失败：{}", ex.getMessage());
            return "";
        }
    }

    private static String abbreviate(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= ERROR_BODY_PREVIEW_LENGTH
                ? (single.isEmpty() ? "无法解析的上游响应" : single)
                : single.substring(0, ERROR_BODY_PREVIEW_LENGTH) + "…";
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
