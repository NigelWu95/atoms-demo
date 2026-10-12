package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactService;
import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EngineeringExportIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;

    @Test void exportsCurrentAndHistoryWithExactManifestWithoutChangingPointers() throws Exception {
        var browser = new ApiBrowser(port);
        var owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = project(browser);
        var one = artifacts.save(owner, project, 0, null, source("第一版"));
        var two = artifacts.save(owner, project, 1, one.id(), source("第二版"));
        var before = browser.get("/api/projects/" + project).body();
        for (var version : java.util.List.of(one, two)) {
            String path = path(project, version.id());
            var reply = browser.download(path);
            assertThat(reply.statusCode()).isEqualTo(200);
            assertThat(reply.headers().firstValue("content-type")).contains("application/zip");
            assertThat(reply.headers().firstValue("content-length")).contains(Integer.toString(reply.body().length));
            assertThat(reply.headers().firstValue("content-disposition"))
                    .contains("attachment; filename=\"atoms-" + project + "-v" + version.versionNo() + "-engineering.zip\"");
            securityHeaders(reply);
            assertThat(browser.download(path).body()).isEqualTo(reply.body());
            var entries = entries(reply.body());
            var manifest = json.readTree(entries.get("bundle-manifest.json"));
            assertThat(entries).hasSize(111);
            assertThat(manifest.path("sourceVersionId").asText()).isEqualTo(version.id().toString());
            assertThat(manifest.path("sourceContentHash").asText()).isEqualTo(version.contentHash());
            assertThat(manifest.path("dataMode").asText()).isEqualTo("INITIAL_ONLY");
            assertThat(json.readTree(entries.get("application/manifest.json")).path("initialState").path("count").asInt()).isZero();
            assertThat(entries.get("application/view.html")).isEqualTo(version.artifact().files().get("view.html").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (var entry : manifest.path("files")) {
                byte[] bytes = entries.get(entry.path("path").asText());
                assertThat(bytes).hasSize(entry.path("size").asInt());
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                        .isEqualTo(entry.path("sha256").asText());
            }
        }
        assertThat(browser.get("/api/projects/" + project).body()).isEqualTo(before);
    }

    @Test void refusesAnonymousOtherOwnerAndVersionFromAnotherProject() throws Exception {
        var ownerBrowser = new ApiBrowser(port);
        var owner = UUID.fromString(ownerBrowser.initialize().body().path("userId").asText());
        String project = project(ownerBrowser);
        var version = artifacts.save(owner, project, 0, null, source("授权版本"));
        var stranger = new ApiBrowser(port);
        stranger.initialize();
        assertError(new ApiBrowser(port).download(path(project, version.id())), 401, "SESSION_REQUIRED");
        assertError(stranger.download(path(project, version.id())), 404, "PROJECT_NOT_FOUND");
        assertError(ownerBrowser.download(path(project(ownerBrowser), version.id())), 404, "VERSION_NOT_FOUND");
        assertError(ownerBrowser.download(path(project, UUID.randomUUID())), 404, "VERSION_NOT_FOUND");
    }

    private void assertError(HttpResponse<byte[]> reply, int status, String code) throws Exception {
        assertThat(reply.statusCode()).isEqualTo(status);
        assertThat(reply.headers().firstValue("content-disposition")).isEmpty();
        assertThat(json.readTree(reply.body()).path("error").path("code").asText()).isEqualTo(code);
        assertThat(json.readTree(reply.body()).path("requestId").asText()).isNotBlank();
        securityHeaders(reply);
    }
    private void securityHeaders(HttpResponse<?> reply) {
        assertThat(reply.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(reply.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(reply.headers().firstValue("x-frame-options")).contains("DENY");
        assertThat(reply.headers().firstValue("content-security-policy").orElseThrow()).contains("frame-ancestors 'none'");
    }
    private String project(ApiBrowser browser) throws Exception {
        return browser.request("POST", "/api/projects", "{\"name\":\"工程下载测试\"}", UUID.randomUUID().toString()).body().path("id").asText();
    }
    private com.fasterxml.jackson.databind.JsonNode source(String title) {
        return json.valueToTree(new Artifact(1, new Artifact.Manifest(title, "受控夹具", "web-static-v1", 1,
                json.createObjectNode().put("count", 0)), Map.of("view.html", "<main><h1>" + title + "</h1></main>",
                "styles.css", "body { color: black; }", "main.js", "// reviewed fixture")));
    }
    private String path(String project, UUID version) { return "/api/projects/" + project + "/versions/" + version + "/engineering.zip"; }
    private Map<String, byte[]> entries(byte[] bytes) throws Exception {
        var entries = new TreeMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) assertThat(entries.put(entry.getName(), zip.readAllBytes())).isNull();
        }
        return entries;
    }
}
