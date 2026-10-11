package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ApiBoundaryIT {
    private ServletWebServerApplicationContext context;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void start() throws Exception {
        TestDatabase.resetInstallation();
        var env = TestDatabase.load();
        env.getPropertySources().addFirst(new MapPropertySource("httpTest", Map.of(
                "server.port", 0, "spring.main.banner-mode", "off", "logging.level.root", "WARN")));
        var app = TestApplication.application();
        app.setEnvironment(env);
        context = (ServletWebServerApplicationContext) app.run();
        var mappings = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        for (String fault : new String[] {"internal", "database"}) {
            var info = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/health")
                    .methods(org.springframework.web.bind.annotation.RequestMethod.GET).params("fault=" + fault)
                    .options(mappings.getBuilderConfiguration()).build();
            mappings.registerMapping(info, new Failures(), Failures.class.getDeclaredMethod(fault));
        }
    }

    @AfterAll void stop() { if (context != null) context.close(); }

    @Test
    void unknownApiIs404JsonEvenWhenTheClientAsksForHtml() throws Exception {
        assertError(send("GET", "/api/not-a-route", null, false), 404, "NOT_FOUND");
    }

    @Test
    void protectedReadsAndWritesHaveConsistent401And403() throws Exception {
        assertError(send("GET", "/api/state", null, false), 401, "SESSION_REQUIRED");
        assertError(send("POST", "/api/session", "{}", false), 403, "CSRF_REJECTED");
    }

    @Test
    void bodyLimitCountsBytesForBothKnownLengthAndChunkedRequests() throws Exception {
        String exact = "{\"x\":\"" + "a".repeat(320 * 1024 - 8) + "\"}";
        assertThat(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(320 * 1024);
        assertError(send("POST", "/api/session", exact, false), 403, "CSRF_REJECTED");
        assertError(send("POST", "/api/session", exact + " ", false), 413, "PAYLOAD_TOO_LARGE");
        assertError(send("POST", "/api/session", exact + " ", true), 413, "PAYLOAD_TOO_LARGE");
        assertError(send("POST", "/api/session", "{\"x\":\"" + "中".repeat(110000) + "\"}", true), 413, "PAYLOAD_TOO_LARGE");
    }

    @Test
    void malformedDuplicateTrailingAndOverdeepJsonAreRejectedBeforeSecurity() throws Exception {
        for (String body : new String[] {"{", "{} {}", "{\"x\":1,\"x\":2}", " ", "[".repeat(65) + "0" + "]".repeat(65)})
            assertError(send("POST", "/api/session", body, false), 422, "INVALID_INPUT");
        assertError(send("POST", "/api/session", "[".repeat(64) + "0" + "]".repeat(64), false), 403, "CSRF_REJECTED");
    }

    @Test
    void nonJsonWritesAreRejected() throws Exception {
        var response = http.send(base("/api/session").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 422, "INVALID_INPUT");
    }

    @Test
    void controllerFailuresAreSanitizedAndCorrelateWithLogs(CapturedOutput output) throws Exception {
        var failed = send("GET", "/api/health?fault=internal", null, false);
        assertError(failed, 500, "INTERNAL_ERROR");
        assertError(send("GET", "/api/health?fault=database", null, false), 503, "SERVICE_UNAVAILABLE");
        assertThat(output.getOut()).contains("\"event\":\"api_rejected\"", "\"requestId\":\""
                        + failed.headers().firstValue("x-request-id").orElseThrow() + "\"")
                .doesNotContain("failure-canary", "SELECT secret", "stack_trace");
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path))
                .timeout(Duration.ofSeconds(5)).header("Accept", "text/html")
                .header("X-Request-Id", "untrusted-canary");
    }

    private HttpResponse<String> send(String method, String path, String body, boolean chunked) throws Exception {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                : HttpRequest.BodyPublishers.ofString(body);
        return http.send(base(path).setHeader("Accept", path.contains("?fault=") ? "application/json" : "text/html")
                        .header("Content-Type", "application/json").method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("error").path("retryable").asBoolean()).isEqualTo(status >= 500);
        assertThat(body.path("requestId").asText()).isEqualTo(response.headers().firstValue("x-request-id").orElseThrow())
                .isNotEqualTo("untrusted-canary");
        assertThat(response.body()).doesNotContain("failure-canary", "jdbc:", "SELECT", "Exception", "<html");
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(response.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("content-security-policy")).isPresent();
        assertThat(response.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @RestController
    @org.springframework.context.annotation.Profile("boundary-fixtures")
    static class Failures {
        void internal() { throw new IllegalStateException("/private/failure-canary"); }
        void database() { throw new DataAccessResourceFailureException("SELECT secret failure-canary"); }
    }
}
