package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class HealthIT {
    private ServletWebServerApplicationContext context;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void startOnlyAfterVerifyingDedicatedTestDatabase() {
        TestDatabase.resetInstallation();
        start();
    }

    private void start() {
        var environment = TestDatabase.load();
        environment.getPropertySources().addFirst(new MapPropertySource("httpTest", Map.of(
                "server.port", 0, "spring.main.banner-mode", "off", "logging.level.root", "WARN")));
        var application = new SpringApplication(StandaloneApplication.class);
        application.setEnvironment(environment);
        application.setAddCommandLineProperties(false);
        context = (ServletWebServerApplicationContext) application.run();
    }

    @AfterEach
    void stopOnlyThisTestsApplication() {
        if (context != null) context.close();
    }

    @Test
    void publicHealthUsesRealDatabaseWithNoCookieAndProtectiveHeaders() throws Exception {
        var response = request("GET", "/api/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(response.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("x-frame-options")).hasValue("DENY");
        assertThat(response.headers().firstValue("content-security-policy")).isPresent();
        assertThat(UUID.fromString(response.headers().firstValue("x-request-id").orElseThrow())).isNotNull();
    }

    @Test
    void connectionLossReturnsSanitized503WithoutStoppingSharedPostgres(CapturedOutput output) throws Exception {
        context.getBean(HikariDataSource.class).close();
        var response = request("GET", "/api/health");
        assertThat(response.statusCode()).isEqualTo(503);
        var body = json.readTree(response.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(body.path("error").path("retryable").asBoolean()).isTrue();
        assertThat(body.path("requestId").asText()).isEqualTo(response.headers().firstValue("x-request-id").orElseThrow());
        assertThat(UUID.fromString(body.path("requestId").asText())).isNotNull();
        assertThat(response.body()).doesNotContain("jdbc:", "SQLException", "Hikari", "atoms_standalone", "password", "stackTrace");
        assertThat(output.getOut()).contains("\"event\":\"health_checked\"", "\"entryPoint\":\"health\"",
                "\"requestId\":\"" + body.path("requestId").asText() + "\"", "\"status\":503", "\"durationMs\":")
                .doesNotContain("Using generated security password", "jdbc:", "SQLException", "stack_trace");
        TestDatabase.load(); // PostgreSQL itself remains available to other local services.
    }

    @Test
    void wrongInstallationIdentityIsNotReportedAsHealthy() throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        UUID original = UUID.fromString(context.getEnvironment().getRequiredProperty("standalone.instance-id"));
        UUID other = UUID.randomUUID();
        jdbc.update("UPDATE standalone_app.installation SET instance_id = ?", other);
        try { assertThat(request("GET", "/api/health").statusCode()).isEqualTo(503); }
        finally { jdbc.update("UPDATE standalone_app.installation SET instance_id = ? WHERE instance_id = ?", original, other); }
    }

    @Test
    void restartingTheWholeApplicationKeepsExistingVisitorAndSessionTables() throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        UUID visitor = UUID.randomUUID();
        jdbc.update("INSERT INTO standalone_app.guest_users(id) VALUES (?)", visitor);
        String session = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO standalone_app.spring_session
                (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)
                VALUES (?, ?, 1, 1, 604800, 9999999999999)
                """, session, session);
        context.close();
        start();
        assertThat(request("GET", "/api/health").statusCode()).isEqualTo(200);
        var restarted = context.getBean(JdbcTemplate.class);
        assertThat(restarted.queryForObject("SELECT count(*) FROM standalone_app.guest_users WHERE id = ?", Integer.class, visitor)).isEqualTo(1);
        assertThat(restarted.queryForObject("SELECT count(*) FROM standalone_app.spring_session WHERE session_id = ?", Integer.class, session)).isEqualTo(1);
    }

    @Test
    void healthDoesNotOpenOtherEndpointsOrWriteMethods() throws Exception {
        assertThat(request("POST", "/api/health").statusCode()).isIn(401, 403, 405);
        assertThat(request("GET", "/api/session").statusCode()).isIn(401, 403);
        assertThat(request("GET", "/login").statusCode()).isIn(401, 403);
    }

    @Test
    void connectionLossWithAnExistingSessionCookieStillReturns503() throws Exception {
        String session = UUID.randomUUID().toString();
        context.getBean(JdbcTemplate.class).update("""
                INSERT INTO standalone_app.spring_session
                (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)
                VALUES (?, ?, 1, 1, 604800, 9999999999999)
                """, session, session);
        String cookie = context.getEnvironment().getRequiredProperty("server.servlet.session.cookie.name") + "="
                + java.util.Base64.getEncoder().encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        context.getBean(HikariDataSource.class).close();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/api/health"))
                .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test
    void healthCookieExceptionDoesNotChangeOtherPathsOrCookieSecuritySettings() {
        String id = UUID.randomUUID().toString();
        String name = context.getEnvironment().getRequiredProperty("server.servlet.session.cookie.name");
        var request = new MockHttpServletRequest("POST", "/api/session");
        request.setCookies(new jakarta.servlet.http.Cookie(name,
                java.util.Base64.getEncoder().encodeToString(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var serializer = context.getBean(CookieSerializer.class);
        assertThat(serializer.readCookieValues(request)).containsExactly(id);
        request.setMethod("GET");
        request.setRequestURI("/api/health");
        assertThat(serializer.readCookieValues(request)).isEmpty();
        request.setRequestURI("/api/health/extra");
        assertThat(serializer.readCookieValues(request)).containsExactly(id);
        var response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, id));
        assertThat(response.getHeader("Set-Cookie")).startsWith(name + "=")
                .contains("Path=/", "Max-Age=604800", "HttpOnly", "SameSite=Lax").doesNotContain("Secure");
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        int port = context.getWebServer().getPort();
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
