package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionHttpRequestTest {
    @Test void preservesOnlyRelativeApplicationRoutesAndSeparatelyValidatedQuery() {
        for (String path : List.of("/", "/index.html", "/assets/index-123.js", "/assets/%E4%B8%AD.css", "/api/todos/1")) {
            var request = request("GET", path, "after=1&name=a+b", Map.of("Accept", "*/*"), new byte[0]);
            assertThat(request.path()).isEqualTo(path);
            assertThat(request.query()).isEqualTo("after=1&name=a+b");
            assertThat(request.headers()).containsExactlyEntriesOf(Map.of("accept", "*/*"));
        }
    }

    @Test void refusesTraversalDoubleEncodingAbsoluteTargetsAndManagementRoutes() {
        for (String path : List.of("", "http://127.0.0.1/", "//evil/assets/x", "/assets//a.js", "/assets/../api/x",
                "/assets/%2e%2e/x", "/assets/%252e%252e/x", "/assets/a%2fb", "/assets/a%5cb", "/assets/a\\b",
                "/assets/a;param", "/assets/x?query", "/assets/x#fragment", "/assets/user@host", "/assets/%00",
                "/assets/%ff", "/assets/%zz", "/assets/%", "/assets/", "/favicon.ico", "/actuator/health", "/api/actuator/health",
                "/api/management/x", "/api/x/./y", "/api/", "/api/x/", "/api/x\n"))
            assertRejected(() -> request("GET", path, null, Map.of(), new byte[0]), 422);
        for (String query : List.of("x=\r\nHeader:bad", "x=%00", "x=%ff", "x=%", "x=#fragment", "x=a\\b", "x=a b", "x=" + "a".repeat(2048)))
            assertRejected(() -> request("GET", "/api/todos", query, Map.of(), new byte[0]), 422);
    }

    @Test void onlyJsonApiWritesAndBodylessReadsAreAccepted() {
        var headers = Map.of("Content-Type", "application/json; charset=UTF-8");
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            var request = request(method, "/api/todos", null, headers, "{\"title\":\"中文\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(request.write()).isTrue();
        }
        assertThat(request("DELETE", "/api/todos/1", null, Map.of(), new byte[0]).write()).isTrue();
        for (String method : List.of("OPTIONS", "CONNECT", "TRACE", "get", "GET\r\n"))
            assertRejected(() -> request(method, "/api/todos", null, Map.of(), new byte[0]), 422);
        assertRejected(() -> request("POST", "/assets/a.js", null, headers, "{}".getBytes(StandardCharsets.UTF_8)), 422);
        assertRejected(() -> request("GET", "/api/todos", null, headers, "{}".getBytes(StandardCharsets.UTF_8)), 422);
        for (byte[] body : List.of(new byte[0], "{bad}".getBytes(StandardCharsets.UTF_8), "{} {}".getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_16), new byte[]{(byte) 0xff}))
            assertRejected(() -> request("POST", "/api/todos", null, headers, body), 422);
        for (String type : List.of("text/plain", "application/json; charset=utf-16", "application/json; x=y"))
            assertRejected(() -> request("POST", "/api/todos", null, Map.of("Content-Type", type), "{}".getBytes(StandardCharsets.UTF_8)), 422);
        assertRejected(() -> request("POST", "/api/todos", null, headers, new byte[(1 << 20) + 1]), 413);
    }

    @Test void credentialsUnknownHeadersAndHeaderInjectionNeverReachAnUpstreamRequest() {
        for (String name : List.of("Cookie", "Authorization", "Host", "Origin", "Referer", "Forwarded", "X-Forwarded-Host", "Connection", "Transfer-Encoding"))
            assertRejected(() -> request("GET", "/api/todos", null, Map.of(name, "PRIVATE_CANARY"), new byte[0]), 422);
        for (Map<String, String> headers : List.of(Map.of("Accept", "application/json\r\nX: value"), Map.of("If-Match", "x".repeat(513)),
                Map.of("Accept", "a", "accept", "b")))
            assertRejected(() -> request("GET", "/api/todos", null, headers, new byte[0]), 422);
        var accepted = request("GET", "/api/todos", "canary=PRIVATE_CANARY",
                Map.of("Accept", "application/json", "If-Match", "\"v1\"", "Idempotency-Key", "a"), new byte[0]);
        assertThat(accepted.toString()).doesNotContain("PRIVATE_CANARY", "canary", "v1");
    }

    @Test void callerCannotChangeAnAlreadyValidatedRequest() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var headers = new HashMap<>(Map.of("Content-Type", "application/json"));
        var request = request("POST", "/api/todos", null, headers, body);
        body[0] = 0; headers.put("Cookie", "PRIVATE_CANARY"); request.body()[0] = 0;
        assertThat(request.body()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(request.headers()).containsOnlyKeys("content-type");
        assertThatThrownBy(() -> request.headers().put("Cookie", "x")).isInstanceOf(UnsupportedOperationException.class);
    }

    private ExecutionHttpRequest request(String method, String path, String query, Map<String, String> headers, byte[] body) {
        return new ExecutionHttpRequest(method, path, query, headers, body);
    }
    private void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(status))
                .hasNoCause().hasMessageNotContaining("PRIVATE_CANARY");
    }
}
