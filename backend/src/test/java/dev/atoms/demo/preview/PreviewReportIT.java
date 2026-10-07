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
class PreviewReportIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;
    private final String passed = """
            {"status":"PASSED","checks":["SCRIPT_PARSE","SDK_STARTUP","RUNTIME_ERRORS"],"errors":[],"viewport":{"width":1440,"height":900}}
            """;
    record Setup(ApiBrowser browser, UUID owner, String project, ArtifactService.Version version, String path) {}
    Setup setup() throws Exception {
        var browser = new ApiBrowser(port);
        UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"预览报告测试\"}", UUID.randomUUID().toString()).body().path("id").asText();
        var source = json.readTree("""
                {"formatVersion":1,"manifest":{"title":"报告测试","summary":"手工产物","runtime":"web-static-v1","stateSchemaVersion":1,"initialState":{}},
                "files":{"view.html":"<main>报告测试</main>","styles.css":"","main.js":"Atoms.ready(() => {});"}}
                """);
        var version = artifacts.save(owner, project, 0, null, source);
        return new Setup(browser, owner, project, version, "/api/projects/" + project + "/versions/" + version.id() + "/preview-reports");
    }
    @Test void persistsVersionBoundReportsAndReplaysWithoutReplacingALaterFailure() throws Exception {
        var s = setup();
        assertThat(s.browser.get(s.path + "/latest").body().path("status").asText()).isEqualTo("UNKNOWN");
        String key = UUID.randomUUID().toString();
        var first = s.browser.request("POST", s.path, passed, key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(s.browser.request("POST", s.path, passed, key).body()).isEqualTo(first.body());
        var failed = json.readTree(passed).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) failed).put("status", "FAILED").putArray("errors").add("应用启动错误");
        assertThat(s.browser.request("POST", s.path, failed.toString(), key).status()).isEqualTo(422);
        assertThat(s.browser.request("POST", s.path, failed.toString(), UUID.randomUUID().toString()).status()).isEqualTo(201);
        s.browser.request("POST", s.path, passed, key);
        var latest = s.browser.get(s.path + "/latest").body();
        assertThat(latest.path("status").asText()).isEqualTo("FAILED");
        assertThat(latest.path("errors").get(0).asText()).isEqualTo("应用启动错误");
        assertThat(latest.path("viewport").path("width").asInt()).isEqualTo(1440);
        assertThat(s.browser.get("/api/projects/" + s.project).body().path("projectRevision").asLong()).isEqualTo(1);
    }
    @Test void deniesOtherOwnersCrossProjectVersionsAndMissingCsrf() throws Exception {
        var s = setup(); var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.get(s.path + "/latest").status()).isEqualTo(404);
        assertThat(stranger.request("POST", s.path, passed, "report").status()).isEqualTo(404);
        assertThat(new ApiBrowser(port).get(s.path + "/latest").status()).isEqualTo(401);
        assertThat(s.browser.request("POST", s.path, passed, "report", null).status()).isEqualTo(403);
        String other = s.browser.request("POST", "/api/projects", "{\"name\":\"其他报告项目\"}", UUID.randomUUID().toString()).body().path("id").asText();
        assertThat(s.browser.request("POST", s.path.replace(s.project, other), passed, "report").status()).isEqualTo(404);
    }
    @Test void rejectsMalformedReportsAndNewReportsForOldVersions() throws Exception {
        var s = setup();
        for (String invalid : new String[]{passed.replace("PASSED", "UNKNOWN"), passed.replace("\"SDK_STARTUP\",", ""),
                passed.replace("SDK_STARTUP", "SCRIPT_PARSE"), passed.replace("RUNTIME_ERRORS", "PRIVATE_CHECK"),
                passed.replace("1440", "0"), passed.replace("1440", "1.5"), passed.replace("1440", "10001"),
                passed.replace("\"errors\":[]", "\"errors\":[\"unexpected\"]"), passed.replace("PASSED", "FAILED"),
                passed.replace("\"status\":", "\"actorId\":\"spoof\",\"status\":"),
                passed.replace("PASSED", "FAILED").replace("\"errors\":[]", "\"errors\":[\"" + "x".repeat(1001) + "\"]")}) {
            assertThat(s.browser.request("POST", s.path, invalid, UUID.randomUUID().toString()).status()).isEqualTo(422);
        }
        assertThat(s.browser.request("POST", s.path, passed, "old").status()).isEqualTo(201);
        artifacts.save(s.owner, s.project, 1, s.version.id(), json.valueToTree(s.version.artifact()));
        assertThat(s.browser.request("POST", s.path, passed, "old").status()).isEqualTo(201);
        assertThat(s.browser.request("POST", s.path, passed, "new").status()).isEqualTo(409);
        assertThat(s.browser.get(s.path + "/latest").body().path("status").asText()).isEqualTo("PASSED");
    }
}
