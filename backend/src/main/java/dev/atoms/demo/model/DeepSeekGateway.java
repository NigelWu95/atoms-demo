package dev.atoms.demo.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekGateway implements ModelGateway {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String key;
    private final String model;
    private final Duration timeout;
    private final int maxTokens;

    @Autowired public DeepSeekGateway(ObjectMapper json, Environment env) {
        this(json, URI.create("https://api.deepseek.com/chat/completions"),
                env.getProperty("DEEPSEEK_API_KEY", ""), "deepseek-flash",
                Duration.ofSeconds(env.getProperty("DEEPSEEK_TIMEOUT_SECONDS", Integer.class, 120)),
                env.getProperty("DEEPSEEK_MAX_TOKENS", Integer.class, 16384));
    }
    // Package-private test transport seam; no runtime configuration accepts an arbitrary upstream URL.
    DeepSeekGateway(ObjectMapper json, URI endpoint, String key, String model, Duration timeout, int maxTokens) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(180)) > 0
                || maxTokens < 1 || maxTokens > 32768) throw new IllegalArgumentException("Invalid model limits");
        this.json = json; this.endpoint = endpoint; this.key = key.strip(); this.model = model;
        this.timeout = timeout; this.maxTokens = maxTokens;
    }
    public boolean configured() { return !key.isBlank() && !key.startsWith("replace_"); }

    // https://api-docs.deepseek.com/zh-cn/api/create-chat-completion
    public Answer complete(String system, String user, BooleanSupplier cancelled) {
        if (!configured()) throw new ModelException("MODEL_NOT_CONFIGURED", "尚未配置 DeepSeek API Key。");
        if (cancelled.getAsBoolean()) throw cancelled();
        var input = json.createObjectNode().put("model", model).put("stream", false)
                .put("reasoning_effort", "high").put("max_tokens", maxTokens);
        input.putObject("thinking").put("type", "enabled");
        input.putObject("response_format").put("type", "json_object");
        var messages = input.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.ofString(input.toString())).build();
            future = client.sendAsync(request, info -> new LimitedBody());
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (cancelled.getAsBoolean()) throw cancelled();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ModelException("MODEL_TIMEOUT", "模型请求超时，未自动重试。");
                try {
                    var response = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
                    if (response.statusCode() != 200) throw upstream(response.statusCode());
                    return decode(response.body());
                } catch (TimeoutException waiting) { /* Poll cancellation without extending the total deadline. */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw cancelled();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof ModelException error) throw error;
            throw new ModelException("MODEL_UNAVAILABLE", "模型连接失败或超时，未自动重试。");
        } catch (IllegalArgumentException invalid) {
            throw new ModelException("MODEL_CONFIG_INVALID", "模型配置无效，请检查服务端配置。");
        } finally { if (future != null && !future.isDone()) future.cancel(true); }
    }
    private Answer decode(byte[] bytes) {
        try {
            JsonNode root = json.readTree(bytes);
            var choices = root.path("choices");
            if (!choices.isArray() || choices.size() != 1) throw invalid();
            var choice = choices.get(0);
            var counters = root.path("usage");
            var usage = new Usage(count(counters, "prompt_tokens"), count(counters, "completion_tokens"), count(counters, "total_tokens"));
            if (!"stop".equals(choice.path("finish_reason").asText()))
                throw incomplete(choice.path("finish_reason").asText(), usage,
                        count(counters.path("completion_tokens_details"), "reasoning_tokens"));
            var content = choice.path("message").path("content");
            if (!content.isTextual() || content.textValue().isBlank()) throw invalid();
            return new Answer(content.textValue(), usage);
        } catch (java.io.IOException | NullPointerException error) { throw invalid(); }
    }
    private Long count(JsonNode usage, String field) {
        var n = usage.path(field);
        return n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0 ? n.longValue() : null;
    }
    private ModelException upstream(int status) {
        return switch (status) {
            case 401, 403 -> new ModelException("MODEL_AUTH_FAILED", "DeepSeek 认证失败，请检查服务端密钥。");
            case 402 -> new ModelException("MODEL_BALANCE_REQUIRED", "DeepSeek 账户余额不足。");
            case 429 -> new ModelException("MODEL_RATE_LIMITED", "DeepSeek 请求受限，请稍后再发起生成。");
            default -> new ModelException("MODEL_UNAVAILABLE", "DeepSeek 暂不可用或响应异常，未自动重试。");
        };
    }
    private ModelException invalid() { return new ModelException("MODEL_INVALID_RESPONSE", "模型响应格式不正确，未保存为新版本。"); }
    private ModelException incomplete(String reason, Usage usage, Long reasoningTokens) {
        String safeReason = switch (reason) {
            case "length", "content_filter", "insufficient_system_resource", "aborted", "tool_calls" -> reason;
            default -> "other";
        };
        String message = switch (safeReason) {
            case "length" -> "模型达到输出或上下文长度限制，未保存为新版本。";
            case "content_filter" -> "模型输出被上游过滤，未保存为新版本。";
            case "insufficient_system_resource" -> "上游推理资源不足，未保存为新版本。";
            case "aborted" -> "上游中断了生成，未保存为新版本。";
            case "tool_calls" -> "模型返回了不支持的工具调用，未保存为新版本。";
            default -> "模型输出未完整结束，未保存为新版本。";
        };
        return new ModelException("MODEL_INCOMPLETE", message,
                new ModelException.CompletionDetails(safeReason, usage, reasoningTokens, maxTokens));
    }
    private ModelException cancelled() { return new ModelException("RUN_CANCELLED", "生成已取消。"); }
}
