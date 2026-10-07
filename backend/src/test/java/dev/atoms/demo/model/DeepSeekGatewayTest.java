package dev.atoms.demo.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeepSeekGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;
    private URI endpoint;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile long delayMillis;
    private volatile String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"title\\\":\\\"完成\\\"}\",\"reasoning_content\":\"private reasoning\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30,\"total_tokens\":50}}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only-key");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    }
    @AfterEach void stop() { server.stop(0); pool.shutdownNow(); }
    private DeepSeekGateway gateway() { return new DeepSeekGateway(json, endpoint, "test-only-key", "deepseek-flash", Duration.ofSeconds(2), 8192); }

    @Test void sendsDocumentedParametersAndReturnsOnlyFinalContentAndUsage() throws Exception {
        var answer = gateway().complete("Return JSON", "生成待办", () -> false);
        var request = json.readTree(received.get());
        assertThat(request.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(request.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(answer.content()).isEqualTo("{\"title\":\"完成\"}");
        assertThat(answer.usage().totalTokens()).isEqualTo(50);
        assertThat(answer.toString()).doesNotContain("private reasoning", "test-only-key");
    }
    @Test void mapsUpstreamErrorsWithoutLeakingBodyOrRetrying() {
        for (int code : new int[]{401, 402, 429, 500, 503, 302}) {
            status = code; body = "credential-and-prompt-must-not-leak";
            assertThatThrownBy(() -> gateway().complete("JSON", "test", () -> false))
                    .isInstanceOf(ModelException.class).hasMessageNotContaining(body);
        }
        assertThat(calls.get()).isEqualTo(6);
    }
    @Test void rejectsTruncatedMalformedAndOversizedResponses() {
        for (String invalid : new String[]{"not-json", "{}", body.replace("\"stop\"", "\"length\""), "x".repeat(2 * 1024 * 1024 + 1)}) {
            body = invalid;
            assertThatThrownBy(() -> gateway().complete("JSON", "test", () -> false)).isInstanceOf(ModelException.class);
        }
    }
    @Test void distinguishesIncompleteReasonsWithoutExposingUpstreamTextOrRetrying() {
        String complete = body;
        String[][] reasons = {
                {"length", "长度限制"}, {"content_filter", "过滤"},
                {"insufficient_system_resource", "推理资源不足"}, {"aborted", "中断"},
                {"tool_calls", "不支持的工具调用"}, {"private-provider-detail", "未完整结束"}
        };
        for (String[] reason : reasons) {
            body = complete.replace("\"stop\"", "\"" + reason[0] + "\"");
            assertThatThrownBy(() -> gateway().complete("JSON", "test", () -> false))
                    .isInstanceOfSatisfying(ModelException.class, error ->
                            assertThat(error.code()).isEqualTo("MODEL_INCOMPLETE"))
                    .hasMessageContaining(reason[1])
                    .hasMessageNotContaining("private-provider-detail").hasMessageNotContaining("private reasoning")
                    .hasMessageNotContaining("完成").hasMessageNotContaining("test-only-key");
        }
        assertThat(calls.get()).isEqualTo(reasons.length);
    }
    @Test void cancelledAndUnconfiguredCallsNeverReachTheProvider() {
        assertThatThrownBy(() -> gateway().complete("JSON", "test", () -> true))
                .isInstanceOf(ModelException.class).hasMessageContaining("取消");
        var missing = new DeepSeekGateway(json, endpoint, "", "deepseek-flash", Duration.ofSeconds(2), 8192);
        assertThatThrownBy(() -> missing.complete("JSON", "test", () -> false))
                .isInstanceOf(ModelException.class).hasMessageContaining("配置");
        assertThat(calls.get()).isZero();
    }
    @Test void enforcesDeadlineAndCancelsAnInflightRequest() {
        delayMillis = 600;
        var fast = new DeepSeekGateway(json, endpoint, "test-only-key", "deepseek-flash", Duration.ofMillis(150), 8192);
        assertThatThrownBy(() -> fast.complete("JSON", "test", () -> false)).isInstanceOf(ModelException.class);
        int before = calls.get();
        assertThatThrownBy(() -> gateway().complete("JSON", "test", () -> calls.get() > before))
                .isInstanceOf(ModelException.class).hasMessageContaining("取消");
    }
}
