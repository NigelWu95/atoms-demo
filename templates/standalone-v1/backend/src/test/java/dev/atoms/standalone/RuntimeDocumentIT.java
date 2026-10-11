package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class RuntimeDocumentIT {
    private final ObjectMapper json = new ObjectMapper();
    private ServletWebServerApplicationContext context;
    private HttpClient browser;
    private String base;

    @BeforeEach void start() {
        TestDatabase.resetInstallation();
        var environment = TestDatabase.load();
        environment.getPropertySources().addFirst(new MapPropertySource("httpTest", Map.of(
                "server.port", 0, "spring.main.banner-mode", "off", "logging.level.root", "WARN")));
        var files = TestApplication.files();
        files.put("application/styles.css", "/* </style><script>breakout()</script> */".getBytes(StandardCharsets.UTF_8));
        files.put("application/main.js", "const text = '</script><script>breakout()</script>&\\u2028';".getBytes(StandardCharsets.UTF_8));
        var application = TestApplication.application(TestApplication.resources(files));
        application.setEnvironment(environment);
        context = (ServletWebServerApplicationContext) application.run();
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
        browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    @AfterEach void stop() { if (context != null) context.close(); }

    @Test void appDescriptionContainsOnlyCurrentPublicMetadata() throws Exception {
        login();
        var response = get("/api/app?sourceVersionId=foreign");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        var fields = new HashSet<String>(); body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("title", "summary", "runtime", "stateSchemaVersion", "loadedRuntimeId");
        assertThat(body.path("title").asText()).isEqualTo("计数器");
        assertThat(body.path("runtime").asText()).isEqualTo("web-static-v1");
        assertThat(body.path("stateSchemaVersion").intValue()).isEqualTo(1);
        assertThat(body.path("loadedRuntimeId").asText()).isEqualTo(context.getBean(RuntimeResources.class).artifact().loadedRuntimeId());
        assertThat(get("/api/app/other").statusCode()).isEqualTo(404);
    }

    @Test void documentEncodesClosingTagsAndNormalizesAllowedHtml() throws Exception {
        login();
        var response = get("/api/app/document");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("text/html");
        assertThat(response.body()).contains("<button id=\"count\">", "Atoms", "storage", "\\u003c/script\\u003e", "\\u0026")
                .doesNotContain("<script>breakout()", "</style><script>");
        assertThat(response.body().split("</script>", -1)).hasSize(2);
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
    }

    @Test void unauthenticatedClientsCannotReadApplicationOrDocument() throws Exception {
        for (String path : new String[]{"/api/app", "/api/app/document"}) {
            var response = get(path);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo("SESSION_REQUIRED");
            assertThat(response.headers().firstValue("x-frame-options")).hasValue("DENY");
        }
    }

    @Test void onlySuccessfulRuntimeHtmlIsFrameableAndCannotExpandItsPrivileges() throws Exception {
        login();
        var response = get("/api/app/document");
        assertThat(response.headers().firstValue("x-frame-options"))
                .as("Document CSP: %s", response.headers().allValues("content-security-policy")).hasValue("SAMEORIGIN");
        assertThat(response.headers().allValues("content-security-policy")).hasSize(1);
        assertThat(response.headers().firstValue("content-security-policy").orElseThrow())
                .contains("sandbox allow-scripts", "script-src-attr 'none'", "connect-src 'none'", "worker-src 'none'",
                        "frame-src 'none'", "form-action 'none'", "frame-ancestors 'self'")
                .doesNotContain("allow-same-origin", "unsafe-eval", "nonce-", "https:");
        for (String path : new String[]{"/api/app", "/api/health", "/api/app/document/missing"}) {
            var ordinary = get(path);
            assertThat(ordinary.headers().firstValue("x-frame-options")).hasValue("DENY");
            assertThat(ordinary.headers().firstValue("content-security-policy").orElseThrow()).doesNotContain("unsafe-inline");
        }
        assertThat(response.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("referrer-policy")).hasValue("no-referrer");
        assertThat(response.headers().firstValue("permissions-policy")).isPresent();
    }

    @Test void failedFinalSessionCommitRemovesRuntimePermissionsAsWellAsSuccessBody() throws Exception {
        login();
        var mappings = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var info = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/app/document")
                .methods(org.springframework.web.bind.annotation.RequestMethod.GET).params("failSave=true")
                .options(mappings.getBuilderConfiguration()).build();
        mappings.registerMapping(info, new FailedDocument(context.getBean(com.zaxxer.hikari.HikariDataSource.class)),
                FailedDocument.class.getDeclaredMethod("fail", jakarta.servlet.http.HttpServletRequest.class,
                        jakarta.servlet.http.HttpServletResponse.class));
        var response = get("/api/app/document?failSave=true");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("false-success");
        assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.headers().firstValue("x-frame-options")).hasValue("DENY");
        assertThat(response.headers().firstValue("content-security-policy").orElseThrow()).doesNotContain("unsafe-inline");
    }

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.context.annotation.Profile("document-save-failure-fixture")
    static class FailedDocument {
        private final com.zaxxer.hikari.HikariDataSource source;
        FailedDocument(com.zaxxer.hikari.HikariDataSource source) { this.source = source; }
        org.springframework.http.ResponseEntity<String> fail(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response) {
            request.getSession().setAttribute("test-save", "fixture");
            source.close();
            response.setHeader("Content-Security-Policy", RuntimeDocumentPolicy.CSP);
            return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.TEXT_HTML)
                    .body("false-success");
        }
    }

    private void login() throws Exception {
        String csrf = json.readTree(get("/api/csrf").body()).path("token").asText();
        var response = browser.send(request("/api/session").header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    }
}
