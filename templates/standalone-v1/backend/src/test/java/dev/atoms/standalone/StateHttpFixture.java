package dev.atoms.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Only real HTTP and the guarded, dedicated integration database; no production fixture route. */
final class StateHttpFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    ServletWebServerApplicationContext context;
    int port;

    StateHttpFixture() { TestDatabase.resetInstallation(); start(TestApplication.resources()); }
    void start(RuntimeResources resources) {
        var env = TestDatabase.load();
        env.getPropertySources().addFirst(new MapPropertySource("stateHttpTest", Map.of(
                "server.port", port, "spring.main.banner-mode", "off", "logging.level.root", "WARN")));
        var app = TestApplication.application(resources);
        app.setEnvironment(env);
        context = (ServletWebServerApplicationContext) app.run();
        port = context.getWebServer().getPort();
    }
    JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
    String runtimeId() { return context.getBean(RuntimeResources.class).artifact().loadedRuntimeId(); }
    @Override public void close() { if (context != null) context.close(); }

    final class Browser {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;
        UUID user;
        Browser initialize() throws Exception {
            token = body(send("GET", "/api/csrf", null, null)).path("token").asText();
            user = UUID.fromString(body(send("POST", "/api/session", "{}", null)).path("userId").asText());
            token = body(send("GET", "/api/csrf", null, null)).path("token").asText();
            return this;
        }
        HttpResponse<String> send(String method, String path, String body, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
            if (body != null) request.header("Content-Type", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token);
            if (key != null) request.header("Idempotency-Key", key);
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode get() throws Exception { return body(send("GET", "/api/state", null, null)); }
    }
    static JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        return JSON.readTree(response.body());
    }
    static void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var parsed = JSON.readTree(response.body());
        assertThat(parsed.path("error").path("code").asText()).isEqualTo(code);
        assertThat(parsed.path("error").path("retryable").asBoolean()).isEqualTo(status >= 500);
        assertThat(parsed.path("requestId").asText()).isEqualTo(response.headers().firstValue("x-request-id").orElseThrow());
        assertThat(UUID.fromString(parsed.path("requestId").asText())).isNotNull();
    }
}
