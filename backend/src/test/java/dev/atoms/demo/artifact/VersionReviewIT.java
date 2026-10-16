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

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersionReviewIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Test void reviewsActualSnapshotWithRequestAndOwnedBaseline() throws Exception {
        var browser = new ApiBrowser(port); var owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"变更审阅\"}", UUID.randomUUID().toString()).body().path("id").asText();
        var source = ArtifactValidatorTest.source(json);
        var one = artifacts.save(owner, project, 0, null, source);
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("files")).put("styles.css", "main { color: blue; }");
        var two = artifacts.save(owner, project, 1, one.id(), source);
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO runs(id, project_id, owner_id, prompt, intent, expected_project_revision, status, result_version_id) VALUES (?, ?, ?, '改成蓝色', 'MODIFY', 1, 'SUCCEEDED', ?)", run, UUID.fromString(project), owner, two.id());
        String path = "/api/projects/" + project + "/versions/" + two.id() + "/review";
        var review = browser.get(path);
        assertThat(review.status()).isEqualTo(200);
        assertThat(review.body().path("baseVersionId").asText()).isEqualTo(one.id().toString());
        assertThat(review.body().path("request").asText()).isEqualTo("改成蓝色");
        assertThat(review.body().path("runId").asText()).isEqualTo(run.toString());
        assertThat(review.body().path("changes")).hasSize(1);
        assertThat(review.body().path("changes").get(0).path("file").asText()).isEqualTo("styles.css");
        assertThat(review.body().path("changes").get(0).path("before").asText()).contains("font-family");
        assertThat(review.body().path("changes").get(0).path("after").asText()).contains("blue");
        var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.get(path).status()).isEqualTo(404);
        var restore = artifacts.restore(owner, project, json.createObjectNode().put("sourceVersionId", one.id().toString()).put("expectedProjectRevision", 2), "restore");
        var restoredReview = browser.get("/api/projects/" + project + "/versions/" + restore.body().path("id").asText() + "/review");
        assertThat(restoredReview.body().path("restoredFromId").asText()).isEqualTo(one.id().toString());
        assertThat(restoredReview.body().path("baseVersionId").asText()).isEqualTo(two.id().toString());
        assertThat(restoredReview.body().path("request").isNull()).isTrue();
    }
}
