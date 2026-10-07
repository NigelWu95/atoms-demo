package dev.atoms.demo.publication;

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
class PublicationIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    record Setup(ApiBrowser browser, UUID owner, String project, ArtifactService.Version version) {
        String path() { return "/api/projects/" + project + "/publication"; }
        String input(long revision) { return "{\"versionId\":\"" + version.id() + "\",\"expectedProjectRevision\":" + version.versionNo() + ",\"expectedPublicationRevision\":" + revision + "}"; }
    }
    Setup setup() throws Exception {
        var browser = new ApiBrowser(port);
        UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"私有项目名不能公开\"}", UUID.randomUUID().toString()).body().path("id").asText();
        var source = json.readTree("""
                {"formatVersion":1,"manifest":{"title":"公开测试应用","summary":"固定快照","runtime":"web-static-v1","stateSchemaVersion":1,"initialState":{"count":0}},
                "files":{"view.html":"<main>公开版本一</main>","styles.css":"","main.js":"Atoms.ready(() => {});"}}
                """);
        return new Setup(browser, owner, project, artifacts.save(owner, project, 0, null, source));
    }
    void report(Setup s, String status) throws Exception {
        String errors = status.equals("PASSED") ? "[]" : "[\"运行失败\"]";
        assertThat(s.browser.request("POST", "/api/projects/" + s.project + "/versions/" + s.version.id() + "/preview-reports",
                "{\"status\":\"" + status + "\",\"checks\":[\"SCRIPT_PARSE\",\"SDK_STARTUP\",\"RUNTIME_ERRORS\"],\"errors\":" + errors + ",\"viewport\":{\"width\":1200,\"height\":900}}", UUID.randomUUID().toString()).status()).isEqualTo(201);
    }
    @Test void publishesFixedVersionsAndReplaysOldIntentsWithoutChangingThePointer() throws Exception {
        var s = setup(); var anonymous = new ApiBrowser(port);
        assertThat(s.browser.get(s.path()).body().path("revision").asLong()).isZero();
        assertThat(s.browser.request("PUT", s.path(), s.input(0), "publish").status()).isEqualTo(409);
        report(s, "PASSED");
        var published = s.browser.request("PUT", s.path(), s.input(0), "publish");
        assertThat(published.status()).isEqualTo(200);
        String slug = published.body().path("slug").asText();
        assertThat(slug).matches("[0-9a-f]{32}");
        assertThat(published.body().path("publicPath").asText()).isEqualTo("/a/" + slug);
        assertThat(published.body().path("publicUrl").isNull()).isTrue();
        String url = "/api/public/apps/" + slug;
        var meta = anonymous.get(url);
        assertThat(meta.status()).isEqualTo(200);
        assertThat(meta.body().size()).isEqualTo(7);
        assertThat(meta.body().toString()).doesNotContain("ownerId", "projectId", "私有项目名");
        var source = json.valueToTree(s.version.artifact());
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("files")).put("view.html", "<main>公开版本二</main>");
        var v2 = artifacts.save(s.owner, s.project, 1, s.version.id(), source);
        assertThat(anonymous.get(url).body().path("versionId").asText()).isEqualTo(s.version.id().toString());
        var oldDoc = anonymous.document(url + "/document?versionId=" + s.version.id());
        assertThat(oldDoc.statusCode()).isEqualTo(200);
        assertThat(oldDoc.body()).contains("公开版本一").doesNotContain("公开版本二");
        assertThat(oldDoc.headers().firstValue("Content-Security-Policy").orElse("")).contains("sandbox allow-scripts", "connect-src 'none'");
        assertThat(oldDoc.headers().firstValue("X-Frame-Options").orElse("")).isEqualTo("SAMEORIGIN");
        var next = new Setup(s.browser, s.owner, s.project, v2);
        report(next, "PASSED");
        assertThat(s.browser.request("PUT", s.path(), next.input(1), "update").status()).isEqualTo(200);
        assertThat(s.browser.request("PUT", s.path(), s.input(0), "publish").body()).isEqualTo(published.body());
        assertThat(anonymous.get(url).body().path("versionId").asText()).isEqualTo(v2.id().toString());
        assertThat(anonymous.document(url + "/document?versionId=" + s.version.id()).statusCode()).isEqualTo(409);
        assertThat(s.browser.get("/api/projects/" + s.project).body().path("projectRevision").asLong()).isEqualTo(2);
    }
    @Test void unpublishIsNonDestructiveAndOldRetryCannotUnpublishARepublishedApp() throws Exception {
        var s = setup(); report(s, "PASSED");
        var published = s.browser.request("PUT", s.path(), s.input(0), "publish");
        assertThat(published.status()).isEqualTo(200);
        String url = "/api/public/apps/" + published.body().path("slug").asText();
        String deletion = s.path() + "?expectedPublicationRevision=1";
        assertThat(s.browser.request("DELETE", deletion, null, "offline").status()).isEqualTo(204);
        assertThat(new ApiBrowser(port).get(url).status()).isEqualTo(404);
        assertThat(new ApiBrowser(port).document(url + "/document?versionId=" + s.version.id()).statusCode()).isEqualTo(404);
        assertThat(s.browser.get("/api/projects/" + s.project + "/versions/" + s.version.id()).status()).isEqualTo(200);
        var republished = s.browser.request("PUT", s.path(), s.input(2), "republish");
        assertThat(republished.body().path("slug")).isEqualTo(published.body().path("slug"));
        assertThat(s.browser.request("DELETE", deletion, null, "offline").status()).isEqualTo(204);
        assertThat(new ApiBrowser(port).get(url).status()).isEqualTo(200);
        assertThat(s.browser.request("DELETE", deletion, null, "stale").status()).isEqualTo(409);
    }
    @Test void requiresOwnerCsrfCurrentVersionRevisionsAndTheLatestPassingReport() throws Exception {
        var s = setup(); var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.get(s.path()).status()).isEqualTo(404);
        assertThat(stranger.request("PUT", s.path(), s.input(0), "attack").status()).isEqualTo(404);
        assertThat(stranger.request("DELETE", s.path() + "?expectedPublicationRevision=0", null, "attack").status()).isEqualTo(404);
        assertThat(new ApiBrowser(port).get(s.path()).status()).isEqualTo(401);
        assertThat(s.browser.request("PUT", s.path(), s.input(0), "no-csrf", null).status()).isEqualTo(403);
        report(s, "PASSED"); report(s, "FAILED");
        assertThat(s.browser.request("PUT", s.path(), s.input(0), "failed-check").status()).isEqualTo(409);
        report(s, "PASSED");
        assertThat(s.browser.request("PUT", s.path(), s.input(9), "stale").status()).isEqualTo(409);
        for (String input : new String[]{s.input(0).replace("\"expectedProjectRevision\":1", "\"expectedProjectRevision\":1.5"),
                s.input(0).replace("\"expectedPublicationRevision\":0", "\"expectedPublicationRevision\":-1"),
                s.input(0).replace("\"versionId\":", "\"actorId\":\"spoof\",\"versionId\":"), s.input(0).replace(s.version.id().toString(), "not-a-version")})
            assertThat(s.browser.request("PUT", s.path(), input, UUID.randomUUID().toString()).status()).isEqualTo(422);
        assertThat(new ApiBrowser(port).get("/api/public/apps/00000000000000000000000000000000").status()).isEqualTo(404);
    }
    @Test void concurrentPublishHasOneWinnerAndAnActiveRunBlocksANewPublication() throws Exception {
        var s = setup(); report(s, "PASSED");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO runs(id, project_id, owner_id, prompt, intent, base_version_id, expected_project_revision, status) VALUES (?, ?, ?, 'test only', 'MODIFY', ?, 1, 'QUEUED')", run, UUID.fromString(s.project), s.owner, s.version.id());
        jdbc.update("UPDATE projects SET active_run_id = ? WHERE id = ?", run, UUID.fromString(s.project));
        try {
            var blocked = s.browser.request("PUT", s.path(), s.input(0), "active");
            assertThat(blocked.status()).isEqualTo(409);
            assertThat(blocked.body().path("error").path("code").asText()).isEqualTo("RUN_ALREADY_ACTIVE");
        } finally {
            assertThat(s.browser.request("PUT", "/api/runs/" + run + "/cancellation", "{}", null).status()).isEqualTo(200);
        }
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var one = executor.submit(() -> { start.await(); return s.browser.request("PUT", s.path(), s.input(0), "one").status(); });
            var two = executor.submit(() -> { start.await(); return s.browser.request("PUT", s.path(), s.input(0), "two").status(); });
            start.countDown();
            assertThat(java.util.List.of(one.get(15, java.util.concurrent.TimeUnit.SECONDS), two.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(s.browser.get(s.path()).body().path("revision").asLong()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
}
