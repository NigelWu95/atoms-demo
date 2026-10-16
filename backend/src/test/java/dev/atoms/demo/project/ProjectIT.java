package dev.atoms.demo.project;

import dev.atoms.demo.ApiBrowser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectIT {
    @LocalServerPort int port;
    private ApiBrowser guest() throws Exception {
        var browser = new ApiBrowser(port);
        assertThat(browser.initialize().status()).isEqualTo(200);
        return browser;
    }
    @Test
    void createsReplaysRenamesAndKeepsSourceRevisionWithOwnerIsolation() throws Exception {
        var owner = guest();
        String key = UUID.randomUUID().toString();
        var created = owner.request("POST", "/api/projects", "{\"name\":\"  我的项目  \"}", key);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("name").asText()).isEqualTo("我的项目");
        assertThat(created.body().path("projectRevision").asInt()).isZero();
        String path = "/api/projects/" + created.body().path("id").asText();
        var renamed = owner.request("PATCH", path, "{\"name\":\"新名字\"}", null);
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().path("projectRevision").asInt()).isZero();
        assertThat(owner.get(path).body().path("name").asText()).isEqualTo("新名字");
        assertThat(owner.request("POST", "/api/projects", "{\"name\":\"我的项目\"}", key).body())
                .isEqualTo(created.body());
        var mismatch = owner.request("POST", "/api/projects", "{\"name\":\"另一个\"}", key);
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
        assertThat(owner.get("/api/projects").body().path("items")).hasSize(1);
        var stranger = guest();
        assertThat(stranger.get(path).status()).isEqualTo(404);
        assertThat(stranger.request("PATCH", path, "{\"name\":\"攻击\"}", null).status()).isEqualTo(404);
        assertThat(stranger.get("/api/projects").body().path("items")).isEmpty();
        assertThat(stranger.request("POST", "/api/projects", "{\"name\":\"独立意图\"}", key).status()).isEqualTo(201);
    }
    @Test
    void concurrentSameIntentCreatesExactlyOneProject() throws Exception {
        var owner = guest();
        String key = UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(2);
        Callable<ApiBrowser.Reply> submit = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return owner.request("POST", "/api/projects", "{\"name\":\"并发项目\"}", key);
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(submit);
            var second = pool.submit(submit);
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.status()).isEqualTo(201);
            assertThat(b.status()).isEqualTo(201);
            assertThat(a.body()).isEqualTo(b.body());
            assertThat(owner.get("/api/projects").body().path("items")).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void searchesLiterallySortsByRecentEditsAndArchivesWithoutDeleting() throws Exception {
        var owner = guest();
        String first = owner.request("POST", "/api/projects", "{\"name\":\"alpha%\"}", UUID.randomUUID().toString()).body().path("id").asText();
        String second = owner.request("POST", "/api/projects", "{\"name\":\"beta\"}", UUID.randomUUID().toString()).body().path("id").asText();
        owner.request("PATCH", "/api/projects/" + first, "{\"name\":\"changed%\"}", null);
        var page = owner.get("/api/projects?sort=updated&limit=1");
        assertThat(page.body().path("items").get(0).path("id").asText()).isEqualTo(first);
        String cursor = URLEncoder.encode(page.body().path("nextCursor").asText(), StandardCharsets.UTF_8);
        assertThat(owner.get("/api/projects?sort=updated&limit=1&cursor=" + cursor).body().path("items").get(0).path("id").asText()).isEqualTo(second);
        assertThat(owner.get("/api/projects?sort=created&cursor=" + cursor).status()).isEqualTo(400);
        assertThat(owner.get("/api/projects?q=%25").body().path("items")).hasSize(1);
        String archive = "/api/projects/" + first + "/archive";
        assertThat(owner.request("POST", archive, "{}", null).status()).isEqualTo(200);
        assertThat(owner.request("POST", archive, "{}", null).body().path("archived").asBoolean()).isTrue();
        assertThat(owner.get("/api/projects").body().path("items")).hasSize(1);
        assertThat(owner.get("/api/projects?archived=true").body().path("items")).hasSize(1);
        assertThat(owner.get("/api/projects/" + first).body().path("projectRevision").asLong()).isZero();
        var stranger = guest();
        assertThat(stranger.request("DELETE", archive, null, null).status()).isEqualTo(404);
        assertThat(owner.request("DELETE", archive, null, null).body().path("archived").asBoolean()).isFalse();
        assertThat(owner.get("/api/projects").body().path("items")).hasSize(2);
        assertThat(owner.get("/api/projects?sort=bad").status()).isEqualTo(422);
    }
    @Test
    void paginatesDeterministicallyAndValidatesBoundaries() throws Exception {
        var owner = guest();
        for (int i = 0; i < 3; i++) {
            assertThat(owner.request("POST", "/api/projects", "{\"name\":\"项目" + i + "\"}",
                    UUID.randomUUID().toString()).status()).isEqualTo(201);
        }
        var page = owner.get("/api/projects?limit=2");
        assertThat(page.body().path("items")).hasSize(2);
        var cursor = URLEncoder.encode(page.body().path("nextCursor").asText(), StandardCharsets.UTF_8);
        var tail = owner.get("/api/projects?limit=2&cursor=" + cursor);
        assertThat(tail.body().path("items")).hasSize(1);
        assertThat(tail.body().path("nextCursor").isNull()).isTrue();
        assertThat(tail.body().path("items").get(0).path("name").asText()).isEqualTo("项目0");
        assertThat(owner.get("/api/projects?cursor=bad").status()).isEqualTo(400);
        assertThat(owner.get("/api/projects?limit=101").status()).isEqualTo(422);
        assertThat(owner.get("/api/projects/not-an-id").status()).isEqualTo(404);
        for (String body : new String[]{"{}", "{\"name\":\"  \"}", "{\"name\":42}",
                "{\"name\":\"x\",\"ownerId\":\"other\"}", "{\"name\":\"" + "x".repeat(81) + "\"}"}) {
            assertThat(owner.request("POST", "/api/projects", body, UUID.randomUUID().toString()).status()).isEqualTo(422);
        }
        assertThat(owner.request("POST", "/api/projects", "{\"name\":\"x\"}", null).status()).isEqualTo(422);
        assertThat(owner.request("POST", "/api/projects", "broken-json", "json-key").status()).isEqualTo(400);
        assertThat(owner.request("POST", "/api/projects", "{\"name\":\"" + "x".repeat(80) + "\"}", "boundary-key").status()).isEqualTo(201);
    }
}
