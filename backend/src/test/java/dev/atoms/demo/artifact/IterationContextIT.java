package dev.atoms.demo.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.run.IterationContext;
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
class IterationContextIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired IterationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    void request(UUID owner, String project, ArtifactService.Version version, String prompt) {
        jdbc.update("INSERT INTO runs(id, project_id, owner_id, prompt, intent, expected_project_revision, status, result_version_id) VALUES (?, ?, ?, ?, 'CREATE', 0, 'SUCCEEDED', ?)",
                UUID.randomUUID(), UUID.fromString(project), owner, prompt, version.id());
    }
    @Test void followsRestoredLineageRatherThanChronologicalMessagesAndChecksOwnership() throws Exception {
        var browser = new ApiBrowser(port); var owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"记忆分支\"}", UUID.randomUUID().toString()).body().path("id").asText();
        var one = artifacts.save(owner, project, 0, null, ArtifactValidatorTest.source(json)); request(owner, project, one, "保留计数器");
        var two = artifacts.save(owner, project, 1, one.id(), ArtifactValidatorTest.source(json)); request(owner, project, two, "废弃分支");
        assertThat(context.history(owner, project, two.id()).toString()).contains("保留计数器", "废弃分支");
        var restore = artifacts.restore(owner, project, json.createObjectNode().put("sourceVersionId", one.id().toString()).put("expectedProjectRevision", 2), "restore");
        UUID restored = UUID.fromString(restore.body().path("id").asText());
        assertThat(context.history(owner, project, restored).toString()).contains("保留计数器").doesNotContain("废弃分支");
        assertThatThrownBy(() -> context.history(UUID.randomUUID(), project, restored)).isInstanceOf(dev.atoms.demo.common.ApiException.class);
        assertThat(context.history(owner, project, null)).isEmpty();
    }
    @Test void boundsLongHistoriesWithoutAdditionalModelCalls() throws Exception {
        var browser = new ApiBrowser(port); var owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"有界历史\"}", UUID.randomUUID().toString()).body().path("id").asText();
        UUID base = null;
        for (int i = 0; i < 12; i++) {
            var version = artifacts.save(owner, project, i, base, ArtifactValidatorTest.source(json));
            request(owner, project, version, "需求" + i + "x".repeat(7990)); base = version.id();
        }
        var history = context.history(owner, project, base);
        assertThat(history.size()).isLessThanOrEqualTo(8);
        int chars = 0; for (var item : history) chars += item.path("request").asText().length();
        assertThat(chars).isLessThanOrEqualTo(12000);
        assertThat(history.toString()).contains("需求11").doesNotContain("需求0x");
    }
}
