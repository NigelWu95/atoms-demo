package dev.atoms.demo.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ArtifactIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    @Test void roundTripHistoryPaginationOwnerIsolationAndIdenticalHash() throws Exception {
        var browser = new ApiBrowser(port);
        UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"版本测试\"}", UUID.randomUUID().toString())
                .body().path("id").asText();
        var one = artifacts.save(owner, project, 0, null, ArtifactValidatorTest.source(json));
        var two = artifacts.save(owner, project, 1, one.id(), ArtifactValidatorTest.source(json));
        assertThat(two.contentHash()).isEqualTo(one.contentHash());
        assertThat(two.versionNo()).isEqualTo(2);
        assertThat(browser.get("/api/projects/" + project).body().path("currentVersionId").asText()).isEqualTo(two.id().toString());
        assertThat(browser.get("/api/projects/" + project).body().path("projectRevision").asLong()).isEqualTo(2);
        var page = browser.get("/api/projects/" + project + "/versions?limit=1");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("items").get(0).path("id").asText()).isEqualTo(two.id().toString());
        assertThat(page.body().path("items").get(0).has("artifact")).isFalse();
        var next = browser.get("/api/projects/" + project + "/versions?limit=1&cursor=" + page.body().path("nextCursor").asText());
        assertThat(next.body().path("items").get(0).path("id").asText()).isEqualTo(one.id().toString());
        assertThat(next.body().path("nextCursor").isNull()).isTrue();
        String detail = "/api/projects/" + project + "/versions/" + one.id();
        assertThat(browser.get(detail).body().path("artifact")).isEqualTo(ArtifactValidatorTest.source(json));
        var other = new ApiBrowser(port);
        other.initialize();
        assertThat(other.get(detail).status()).isEqualTo(404);
        assertThat(other.get("/api/projects/" + project + "/versions").status()).isEqualTo(404);
        assertThat(new ApiBrowser(port).get(detail).status()).isEqualTo(401);
        assertThat(browser.get("/api/projects/" + project + "/versions?cursor=not-a-cursor").status()).isEqualTo(400);
        assertThat(browser.get("/api/projects/" + project + "/versions?limit=101").status()).isEqualTo(422);
        assertThat(browser.request("POST", "/api/projects/" + project + "/versions", "{}", "blocked").status()).isEqualTo(405);
        assertThatThrownBy(() -> jdbc.update("UPDATE versions SET source_json = '{}' WHERE id = ?", one.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM versions WHERE id = ?", one.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String secondProject = browser.request("POST", "/api/projects", "{\"name\":\"另一个项目\"}", UUID.randomUUID().toString())
                .body().path("id").asText();
        assertThat(browser.get("/api/projects/" + secondProject + "/versions/" + one.id()).status()).isEqualTo(404);
        assertThatThrownBy(() -> jdbc.update("UPDATE projects SET current_version_id = ? WHERE id = ?", one.id(), UUID.fromString(secondProject)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> artifacts.save(owner, project, 2, one.id(), ArtifactValidatorTest.source(json)))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code()).isEqualTo("BASE_VERSION_CONFLICT");
    }

    @Test void rollbackAndConcurrentBaselineDoNotLeavePartialOrDuplicateVersions() throws Exception {
        var browser = new ApiBrowser(port);
        UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"并发版本\"}", UUID.randomUUID().toString())
                .body().path("id").asText();
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> {
            artifacts.save(owner, project, 0, null, ArtifactValidatorTest.source(json));
            throw new IllegalStateException("deliberate rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM versions WHERE project_id = ?", Integer.class, UUID.fromString(project))).isZero();
        assertThat(browser.get("/api/projects/" + project).body().path("currentVersionId").isNull()).isTrue();
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> save = () -> {
                barrier.await();
                try { artifacts.save(owner, project, 0, null, ArtifactValidatorTest.source(json)); return 201; }
                catch (ApiException error) { return error.status(); }
            };
            var left = pool.submit(save);
            var right = pool.submit(save);
            assertThat(java.util.List.of(left.get(), right.get())).containsExactlyInAnyOrder(201, 409);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM versions WHERE project_id = ?", Integer.class, UUID.fromString(project))).isEqualTo(1);
        assertThat(browser.get("/api/projects/" + project).body().path("projectRevision").asLong()).isEqualTo(1);
    }
}
