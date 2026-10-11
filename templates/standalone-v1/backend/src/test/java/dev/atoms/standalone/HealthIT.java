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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

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
    void connectionLossReturnsSanitized503WithoutStoppingSharedPostgres() throws Exception {
        context.getBean(HikariDataSource.class).close();
        var response = request("GET", "/api/health");
        assertThat(response.statusCode()).isEqualTo(503);
        var body = json.readTree(response.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(body.path("error").path("retryable").asBoolean()).isTrue();
        assertThat(body.path("requestId").asText()).isEqualTo(response.headers().firstValue("x-request-id").orElseThrow());
        assertThat(UUID.fromString(body.path("requestId").asText())).isNotNull();
        assertThat(response.body()).doesNotContain("jdbc:", "SQLException", "Hikari", "atoms_standalone", "password", "stackTrace");
        TestDatabase.load(); // PostgreSQL itself remains available to other local services.
    }

    @Test
    void wrongInstallationIdentityIsNotReportedAsHealthy() throws Exception {
        context.getBean(JdbcTemplate.class).update("UPDATE standalone_app.installation SET instance_id = ?", UUID.randomUUID());
        assertThat(request("GET", "/api/health").statusCode()).isEqualTo(503);
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

    private HttpResponse<String> request(String method, String path) throws Exception {
        int port = context.getWebServer().getPort();
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
