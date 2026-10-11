package dev.atoms.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SessionIT {
    private ServletWebServerApplicationContext context;
    private final ObjectMapper json = new ObjectMapper();
    private int port;

    @BeforeEach void setup() { TestDatabase.resetInstallation(); start(0); }
    private void start(int requestedPort) {
        var env = TestDatabase.load();
        env.getPropertySources().addFirst(new MapPropertySource("httpTest", Map.of(
                "server.port", requestedPort, "spring.main.banner-mode", "off", "logging.level.root", "WARN")));
        var app = new SpringApplication(StandaloneApplication.class);
        app.setEnvironment(env);
        context = (ServletWebServerApplicationContext) app.run();
        port = context.getWebServer().getPort();
    }
    @AfterEach void stop() { if (context != null) context.close(); }

    @Test
    void csrfThenInitializeRotatesCredentialsAndRepeatKeepsOneVisitor() throws Exception {
        var browser = new Browser();
        String beforeToken = browser.csrf();
        String beforeCookie = browser.cookie();
        assertThat(count("guest_users")).isZero();
        assertThat(count("spring_session")).isEqualTo(1);
        var created = browser.send("POST", "/api/session", "{}", beforeToken);
        assertThat(created.statusCode()).isEqualTo(200);
        String id = identity(created);
        assertThat(json.readTree(created.body()).path("isGuest").asBoolean()).isTrue();
        assertThat(browser.cookie()).isNotEqualTo(beforeCookie);
        assertThat(created.headers().firstValue("set-cookie").orElseThrow())
                .contains("HttpOnly", "SameSite=Lax", "Max-Age=604800", "Path=/").doesNotContain("Secure");
        error(browser.send("POST", "/api/session", "{}", beforeToken), 403, "CSRF_REJECTED");
        String token = browser.csrf();
        assertThat(identity(browser.send("POST", "/api/session", "{}", token))).isEqualTo(id);
        assertThat(count("guest_users")).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT max_inactive_interval FROM standalone_app.spring_session", Integer.class))
                .isEqualTo(604800);
        var old = new Browser();
        error(old.rawCookie("/api/app", beforeCookie), 401, "SESSION_REQUIRED");
    }

    @Test
    void onlyEmptyObjectsAreAcceptedWithoutCreatingUsers() throws Exception {
        var browser = new Browser();
        String token = browser.csrf();
        for (String body : new String[] {"[]", "null", "1", "{\"userId\":\"chosen-identity\"}", "{\"x\":null}"})
            error(browser.send("POST", "/api/session", body, token), 422, "INVALID_INPUT");
        assertThat(count("guest_users")).isZero();
        assertThat(identity(browser.send("POST", "/api/session", "{}", token))).isNotBlank();
    }

    @Test
    void twoVisitorsCannotSelectAnotherIdentityOrReuseAnotherCsrfToken() throws Exception {
        var first = new Browser();
        var second = new Browser();
        String a = first.initialize();
        String b = second.initialize();
        assertThat(b).isNotEqualTo(a);
        error(second.send("POST", "/api/session", "{}", first.csrf()), 403, "CSRF_REJECTED");
        String token = second.csrf();
        error(second.send("POST", "/api/session", "{\"userId\":\"" + a + "\"}", token), 422, "INVALID_INPUT");
        assertThat(identity(second.send("POST", "/api/session", "{}", token))).isEqualTo(b);
        assertThat(identity(first.send("POST", "/api/session", "{}", first.csrf()))).isEqualTo(a);
        assertThat(count("guest_users")).isEqualTo(2);
    }

    @Test
    void credentialsPersistAcrossApplicationRestart() throws Exception {
        var browser = new Browser();
        String id = browser.initialize();
        String token = browser.csrf();
        String cookie = browser.cookie();
        context.close();
        start(port);
        assertThat(identity(browser.send("POST", "/api/session", "{}", token))).isEqualTo(id);
        assertThat(browser.cookie()).isEqualTo(cookie);
        assertThat(count("guest_users")).isEqualTo(1);
    }

    @Test
    void missingExpiredPlatformAndForeignInstanceCookiesCannotAuthenticate() throws Exception {
        var browser = new Browser();
        error(browser.send("GET", "/api/app", null, null), 401, "SESSION_REQUIRED");
        error(browser.send("GET", "/api/app/document", null, null), 401, "SESSION_REQUIRED");
        error(browser.send("GET", "/api/state", null, null), 401, "SESSION_REQUIRED");
        browser.initialize();
        String value = browser.cookie().substring(browser.cookie().indexOf('=') + 1);
        error(new Browser().rawCookie("/api/app", "JSESSIONID=" + value), 401, "SESSION_REQUIRED");
        error(new Browser().rawCookie("/api/app", "STANDALONE_foreign=" + value), 401, "SESSION_REQUIRED");
        jdbc().update("UPDATE standalone_app.spring_session SET last_access_time = 1, expiry_time = 1");
        error(browser.send("GET", "/api/app", null, null), 401, "SESSION_REQUIRED");
    }

    @Test
    void missingAndWrongCsrfNeverCreateAVisitor() throws Exception {
        var browser = new Browser();
        error(browser.send("POST", "/api/session", "{}", null), 403, "CSRF_REJECTED");
        browser.csrf();
        error(browser.send("POST", "/api/session", "{}", "invalid-token"), 403, "CSRF_REJECTED");
        assertThat(count("guest_users")).isZero();
    }

    @Test
    void databaseLossWithExistingCookieReturns503WithoutFalseLoginSuccess() throws Exception {
        var browser = new Browser();
        browser.initialize();
        String token = browser.csrf();
        context.getBean(HikariDataSource.class).close();
        error(browser.send("GET", "/api/csrf", null, null), 503, "SERVICE_UNAVAILABLE");
        error(browser.send("POST", "/api/session", "{}", token), 503, "SERVICE_UNAVAILABLE");
        TestDatabase.load();
    }

    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }

    @Test
    void csrfInTheQueryStringIsNotAcceptedAsAHeader() throws Exception {
        var browser = new Browser();
        String token = browser.csrf();
        error(browser.send("POST", "/api/session?_csrf=" + token, "{}", null), 403, "CSRF_REJECTED");
        assertThat(count("guest_users")).isZero();
    }

    @Test
    void foreignOriginCannotInitializeEvenWithValidCookieAndToken() throws Exception {
        var browser = new Browser();
        String token = browser.csrf();
        var response = browser.client.send(browser.base("/api/session").header("Origin", "https://foreign.example")
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        error(response, 403, "FORBIDDEN");
        assertThat(count("guest_users")).isZero();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void failureWhileSavingASessionCannotSendTheBufferedSuccessResponse(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var browser = new Browser();
        browser.csrf();
        var mappings = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var info = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/csrf")
                .methods(org.springframework.web.bind.annotation.RequestMethod.GET).params("failSave=true")
                .options(mappings.getBuilderConfiguration()).build();
        mappings.registerMapping(info, new SessionSaveFailure(context.getBean(HikariDataSource.class)),
                SessionSaveFailure.class.getDeclaredMethod("fail", jakarta.servlet.http.HttpServletRequest.class));
        var response = browser.send("GET", "/api/csrf?failSave=true", null, null);
        error(response, 503, "SERVICE_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("false-success");
        assertThat(output.getOut()).contains("\"code\":\"SERVICE_UNAVAILABLE\"", "\"requestId\":\""
                        + response.headers().firstValue("x-request-id").orElseThrow() + "\"")
                .doesNotContain("stack_trace", "HikariPool-", "SQLException", "failure-canary");
        TestDatabase.load();
    }

    // Installed explicitly in one test only, never component-scanned or shipped in the JAR.
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.context.annotation.Profile("session-save-failure-fixture")
    static class SessionSaveFailure {
        private final HikariDataSource source;
        SessionSaveFailure(HikariDataSource source) { this.source = source; }
        Map<String, String> fail(jakarta.servlet.http.HttpServletRequest request) {
            request.getSession().setAttribute("test-save", "fixture");
            source.close();
            return Map.of("status", "false-success");
        }
    }
    private int count(String table) { // Only fixed, test-owned identifiers above.
        return jdbc().queryForObject("SELECT count(*) FROM standalone_app." + table, Integer.class);
    }
    private String identity(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        String id = json.readTree(response.body()).path("userId").asText();
        assertThat(java.util.UUID.fromString(id)).isNotNull();
        return id;
    }
    private void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("requestId").asText()).isEqualTo(response.headers().firstValue("x-request-id").orElseThrow());
        assertThat(response.body()).doesNotContain("jdbc:", "SELECT", "Exception", "stack_trace");
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        String cookie() {
            var cookie = cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().startsWith("STANDALONE_")).findFirst().orElseThrow();
            return cookie.getName() + "=" + cookie.getValue();
        }
        String csrf() throws Exception {
            var response = send("GET", "/api/csrf", null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json.readTree(response.body());
            assertThat(body.path("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
            assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
            return body.path("token").asText();
        }
        String initialize() throws Exception { return identity(send("POST", "/api/session", "{}", csrf())); }
        HttpResponse<String> rawCookie(String path, String cookie) throws Exception {
            return HttpClient.newHttpClient().send(base(path).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
            var request = base(path).header("Content-Type", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        private HttpRequest.Builder base(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        }
    }
}
