package dev.atoms.demo.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RestorationIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    record Setup(ApiBrowser browser, UUID owner, String project, ArtifactService.Version one, ArtifactService.Version two) {
        String path() { return "/api/projects/" + project + "/restorations"; }
        String input() { return "{\"sourceVersionId\":\"" + one.id() + "\",\"expectedProjectRevision\":2}"; }
    }
    Setup setup() throws Exception {
        var browser = new ApiBrowser(port);
        UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"恢复测试\"}", UUID.randomUUID().toString())
                .body().path("id").asText();
        var source = ArtifactValidatorTest.source(json);
        var one = artifacts.save(owner, project, 0, null, source);
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("files")).put("view.html", "<main>版本二</main>");
        var two = artifacts.save(owner, project, 1, one.id(), source);
        return new Setup(browser, owner, project, one, two);
    }

    @Test void restoresAsNewVersionPreservingHistoryDataAndPublicationAndReplaysOneIntent() throws Exception {
        var s = setup();
        UUID projectId = UUID.fromString(s.project);
        jdbc.update("INSERT INTO app_states(project_id, environment, actor_id, value_json, revision, state_schema_version) VALUES (?, 'PREVIEW', ?, '{\"count\":7}', 1, 1)", projectId, s.owner);
        jdbc.update("INSERT INTO publications(project_id, slug, version_id, status, revision) VALUES (?, ?, ?, 'PUBLISHED', 1)", projectId, UUID.randomUUID().toString().replace("-", ""), s.two.id());
        var response = s.browser.request("POST", s.path(), s.input(), "restore");
        assertThat(response.status()).isEqualTo(201);
        var restored = response.body();
        assertThat(restored.path("versionNo").asLong()).isEqualTo(3);
        assertThat(restored.path("restoredFromId").asText()).isEqualTo(s.one.id().toString());
        assertThat(restored.path("basedOnId").asText()).isEqualTo(s.two.id().toString());
        assertThat(restored.path("artifact")).isEqualTo(json.valueToTree(s.one.artifact()));
        assertThat(restored.path("contentHash").asText()).isEqualTo(s.one.contentHash());
        assertThat(s.browser.get("/api/projects/" + s.project).body().path("currentVersionId")).isEqualTo(restored.path("id"));
        assertThat(s.browser.request("POST", s.path(), s.input(), "restore").body()).isEqualTo(restored);
        assertThat(s.browser.get("/api/projects/" + s.project + "/versions").body().path("items").size()).isEqualTo(3);
        assertThat(artifacts.get(s.owner, s.project, s.two.id().toString()).artifact()).isEqualTo(s.two.artifact());
        assertThat(jdbc.queryForObject("SELECT value_json FROM app_states WHERE project_id = ?", String.class, projectId)).isEqualTo("{\"count\":7}");
        assertThat(jdbc.queryForObject("SELECT version_id FROM publications WHERE project_id = ?", UUID.class, projectId)).isEqualTo(s.two.id());
        assertThat(s.browser.get("/api/projects/" + s.project + "/versions/" + restored.path("id").asText() + "/preview-reports/latest").body().path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(s.browser.request("POST", s.path(), s.input(), "stale-intent").status()).isEqualTo(409);
    }

    @Test void rejectsCrossOwnerCrossProjectInvalidInputAndMissingCsrfWithoutCreatingVersions() throws Exception {
        var s = setup(); var stranger = setup();
        assertThat(stranger.browser.request("POST", s.path(), s.input(), "attack").status()).isEqualTo(404);
        assertThat(s.browser.request("POST", s.path(), s.input(), "csrf", null).status()).isEqualTo(403);
        assertThat(s.browser.request("POST", s.path(), s.input(), null).status()).isEqualTo(422);
        assertThat(s.browser.request("POST", s.path(), s.input().replace(s.one.id().toString(), stranger.one.id().toString()), "cross-project").status()).isEqualTo(404);
        for (String input : new String[]{"{}", "[]", s.input().replace(":2", ":-1"), s.input().replace(":2", ":2.5"),
                s.input().replace(":2", ":9007199254740991"), s.input().replace("}", ",\"extra\":true}")})
            assertThat(s.browser.request("POST", s.path(), input, UUID.randomUUID().toString()).status()).isEqualTo(422);
        assertThat(s.browser.get("/api/projects/" + s.project + "/versions").body().path("items").size()).isEqualTo(2);
        assertThat(s.browser.request("POST", s.path(), s.input(), "restore").status()).isEqualTo(201);
        var mismatch = s.browser.request("POST", s.path(), s.input().replace(s.one.id().toString(), s.two.id().toString()), "restore");
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }

    @Test void blocksActiveRunsAndSerializesCompetingRestorations() throws Exception {
        var s = setup(); UUID run = UUID.randomUUID(); UUID project = UUID.fromString(s.project);
        jdbc.update("INSERT INTO runs(id, project_id, owner_id, prompt, intent, base_version_id, expected_project_revision, status) VALUES (?, ?, ?, 'test only', 'MODIFY', ?, 2, 'QUEUED')", run, project, s.owner, s.two.id());
        jdbc.update("UPDATE projects SET active_run_id = ? WHERE id = ?", run, project);
        try {
            var blocked = s.browser.request("POST", s.path(), s.input(), "restore");
            assertThat(blocked.status()).isEqualTo(409);
            assertThat(blocked.body().path("error").path("code").asText()).isEqualTo("RUN_ALREADY_ACTIVE");
        } finally {
            assertThat(s.browser.request("PUT", "/api/runs/" + run + "/cancellation", "{}", null).status()).isEqualTo(200);
        }
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var one = executor.submit(() -> { start.await(); return s.browser.request("POST", s.path(), s.input(), "one").status(); });
            var two = executor.submit(() -> { start.await(); return s.browser.request("POST", s.path(), s.input(), "two").status(); });
            start.countDown();
            assertThat(java.util.List.of(one.get(15, java.util.concurrent.TimeUnit.SECONDS), two.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
            assertThat(s.browser.get("/api/projects/" + s.project).body().path("projectRevision").asLong()).isEqualTo(3);
        } finally { executor.shutdownNow(); }
    }

    @Test void concurrentDuplicateIntentCreatesOnlyOneVersion() throws Exception {
        var s = setup();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<ApiBrowser.Reply> restore = () -> {
                start.await(); return s.browser.request("POST", s.path(), s.input(), "same-intent");
            };
            var first = executor.submit(restore); var second = executor.submit(restore); start.countDown();
            var one = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var two = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(one.status()).isEqualTo(201);
            assertThat(two.status()).isEqualTo(201);
            assertThat(one.body()).isEqualTo(two.body());
            assertThat(s.browser.get("/api/projects/" + s.project + "/versions").body().path("items").size()).isEqualTo(3);
        } finally { executor.shutdownNow(); }
    }
}
