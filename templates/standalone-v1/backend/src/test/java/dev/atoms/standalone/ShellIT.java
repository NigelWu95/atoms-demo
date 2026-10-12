package dev.atoms.standalone;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class ShellIT {
    private ServletWebServerApplicationContext context;
    private final HttpClient browser = HttpClient.newHttpClient();
    private String base;

    @BeforeEach void start() {
        TestDatabase.resetInstallation();
        var environment = TestDatabase.load();
        environment.getPropertySources().addFirst(new MapPropertySource("shellTest", Map.of(
                "server.port", 0, "spring.main.banner-mode", "off", "logging.level.root", "WARN")));
        var app = TestApplication.application();
        app.setEnvironment(environment);
        context = (ServletWebServerApplicationContext) app.run();
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
    }
    @AfterEach void stop() { if (context != null) context.close(); }

    @Test void servesOnlyVerifiedPublicShellBytesWithoutAnAuthenticatedSession() throws Exception {
        for (String path : new String[]{"/", "/index.html"}) {
            var response = get(path);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Shell fixture");
            assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("text/html");
            assertThat(response.headers().allValues("content-security-policy")).hasSize(1);
            assertThat(response.headers().firstValue("content-security-policy").orElseThrow())
                    .contains("script-src 'self'", "connect-src 'self'", "frame-src 'self'", "frame-ancestors 'none'")
                    .doesNotContain("unsafe-inline", "unsafe-eval");
            assertThat(response.headers().firstValue("set-cookie")).isEmpty();
            assertThat(response.headers().firstValue("x-frame-options")).hasValue("DENY");
            assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        }
        var script = get("/assets/app.js");
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.body()).contains("test shell");
        assertThat(script.headers().firstValue("content-type").orElseThrow()).startsWith("text/javascript");
        assertThat(get("/api/app").statusCode()).isEqualTo(401);
    }

    @Test void missingAssetsAndUnknownApiNeverBecomeASpaSuccessOrExposeSources() throws Exception {
        for (String path : new String[]{"/assets/missing.js", "/favicon.svg", "/api/missing"}) {
            var response = get(path);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).doesNotContain("Shell fixture", "test shell");
            assertThat(response.headers().firstValue("content-security-policy").orElseThrow()).doesNotContain("unsafe-inline");
        }
        for (String path : new String[]{"/application/main.js", "/runtime/build.txt", "/application.yml", "/assets/app.js.map"})
            assertThat(get(path).statusCode()).isBetween(400, 499);
        var post = browser.send(HttpRequest.newBuilder(URI.create(base + "/"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
