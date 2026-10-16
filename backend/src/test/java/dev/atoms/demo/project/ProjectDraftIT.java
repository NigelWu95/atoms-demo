package dev.atoms.demo.project;

import dev.atoms.demo.ApiBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectDraftIT {
    @LocalServerPort int port;
    @Test void concurrentTabsCannotBothCommitDifferentDraftsAtTheSameRevision() throws Exception {
        var owner = new ApiBrowser(port); owner.initialize();
        String id = owner.request("POST", "/api/projects", "{\"name\":\"并发草稿\"}", UUID.randomUUID().toString()).body().path("id").asText();
        String path = "/api/projects/" + id + "/draft";
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return owner.request("PUT", path, "{\"prompt\":\"标签一\",\"expectedRevision\":0}", null); });
            var second = pool.submit(() -> { start.await(); return owner.request("PUT", path, "{\"prompt\":\"标签二\",\"expectedRevision\":0}", null); });
            start.countDown();
            var a = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(a.status(), b.status())).containsExactlyInAnyOrder(200, 409);
            assertThat(owner.get(path).body()).isEqualTo((a.status() == 200 ? a : b).body());
            assertThat(owner.get(path).body().path("revision").asInt()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void savesRestoresAndClearsDraftWithRevisionIsolationAndSafeRetry() throws Exception {
        var owner = new ApiBrowser(port); owner.initialize();
        String id = owner.request("POST", "/api/projects", "{\"name\":\"草稿\"}", UUID.randomUUID().toString()).body().path("id").asText();
        String path = "/api/projects/" + id + "/draft";
        assertThat(owner.get(path).status()).isEqualTo(200);
        assertThat(owner.get(path).body().path("revision").asInt()).isZero();
        String input = "{\"prompt\":\"增加分类\",\"expectedRevision\":0}";
        var saved = owner.request("PUT", path, input, null);
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().path("revision").asInt()).isEqualTo(1);
        assertThat(owner.request("PUT", path, input, null).body()).isEqualTo(saved.body());
        assertThat(owner.get(path).body()).isEqualTo(saved.body());
        assertThat(owner.request("PUT", path, "{\"prompt\":\"旧标签页\",\"expectedRevision\":0}", null).status()).isEqualTo(409);
        var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.get(path).status()).isEqualTo(404);
        assertThat(stranger.request("PUT", path, input, null).status()).isEqualTo(404);
        assertThat(owner.request("PUT", path, "{\"prompt\":\"\",\"expectedRevision\":1}", null).status()).isEqualTo(200);
        assertThat(owner.get(path).body().path("prompt").asText()).isEmpty();
        assertThat(owner.get("/api/projects/" + id).body().path("projectRevision").asLong()).isZero();
        for (String invalid : new String[]{"{}", "{\"prompt\":\"x\",\"expectedRevision\":-1}", "{\"prompt\":\"x\",\"expectedRevision\":1,\"owner\":\"x\"}", "{\"prompt\":\"" + "x".repeat(8001) + "\",\"expectedRevision\":2}"})
            assertThat(owner.request("PUT", path, invalid, null).status()).isEqualTo(422);
    }
}
