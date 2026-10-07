package dev.atoms.demo.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.artifact.ArtifactService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RuntimeDocumentIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;

    @Test void scopedHeadersEscapingModesAndOwnership() throws Exception {
        var browser = new ApiBrowser(port);
        UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"运行文档测试\"}", UUID.randomUUID().toString())
                .body().path("id").asText();
        var source = json.readTree("""
                {"formatVersion":1,"manifest":{"title":"安全测试","summary":"手工测试产物","runtime":"web-static-v1",
                "stateSchemaVersion":1,"initialState":{}},"files":{"view.html":"<main><h1>测试</h1></main>",
                "styles.css":"/* </style><script>breakout()</script> */",
                "main.js":"const text = '</script><script>breakout()</script>';"}}
                """);
        var one = artifacts.save(owner, project, 0, null, source);
        String url = "/api/projects/" + project + "/versions/" + one.id() + "/document";
        var response = browser.document(url + "?previewMode=CURRENT");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("SAMEORIGIN");
        assertThat(response.headers().allValues("Content-Security-Policy")).hasSize(1);
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("sandbox allow-scripts", "connect-src 'none'", "frame-src 'none'", "form-action 'none'", "frame-ancestors 'self'")
                .doesNotContain("allow-same-origin", "unsafe-eval", "nonce-", "https:");
        assertThat(response.body()).doesNotContain("<script>breakout()", "</style><script>")
                .contains("\\u003c/script\\u003e");
        assertThat(response.body().split("</script>", -1)).hasSize(2);
        assertThat(browser.get("/api/projects/" + project).raw().headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("DENY");
        assertThat(browser.get("/api/projects/" + project).raw().headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("default-src 'none'", "frame-ancestors 'none'").doesNotContain("unsafe-inline");
        artifacts.save(owner, project, 1, one.id(), source);
        assertThat(browser.get(url + "?previewMode=CURRENT").status()).isEqualTo(409);
        assertThat(browser.document(url + "?previewMode=HISTORY").statusCode()).isEqualTo(200);
        assertThat(browser.get(url + "?previewMode=LIVE").status()).isEqualTo(422);
        var other = new ApiBrowser(port);
        other.initialize();
        assertThat(other.document(url + "?previewMode=HISTORY").statusCode()).isEqualTo(404);
        var denied = new ApiBrowser(port).document(url);
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("DENY");
        assertThat(browser.get("/api/test-fixtures").status()).isEqualTo(404);
    }
}
