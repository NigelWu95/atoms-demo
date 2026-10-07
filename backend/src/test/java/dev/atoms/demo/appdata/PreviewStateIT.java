package dev.atoms.demo.appdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.artifact.ArtifactService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PreviewStateIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ArtifactService artifacts;
    record Setup(ApiBrowser browser, UUID owner, String project, ArtifactService.Version version) {
        String path() { return "/api/projects/" + project + "/preview-state"; }
    }
    private Setup setup() throws Exception {
        var browser = new ApiBrowser(port);
        UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"状态测试\"}", UUID.randomUUID().toString()).body().path("id").asText();
        return new Setup(browser, owner, project, artifacts.save(owner, project, 0, null, source()));
    }
    private ObjectNode source() {
        var source = json.createObjectNode().put("formatVersion", 1);
        source.putObject("manifest").put("title", "手工状态测试").put("summary", "非 AI 生成")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").put("count", 0);
        source.putObject("files").put("view.html", "<main>手工状态测试</main>").put("styles.css", "").put("main.js", "");
        return source;
    }
    private ObjectNode input(UUID version, long revision, int count) {
        var input = json.createObjectNode().put("loadedVersionId", version.toString()).put("expectedRevision", revision).put("stateSchemaVersion", 1);
        input.putObject("value").put("count", count);
        return input;
    }

    @Test void persistsStateReplaysIntentAndPreservesDataAcrossCodeVersions() throws Exception {
        var s = setup();
        String read = s.path() + "?versionId=" + s.version.id();
        var initial = s.browser.get(read);
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.body().path("revision").asInt()).isZero();
        assertThat(initial.body().path("value").path("count").asInt()).isZero();
        var body = input(s.version.id(), 0, 3).toString();
        var saved = s.browser.request("PUT", s.path(), body, "first-save");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().path("revision").asInt()).isEqualTo(1);
        assertThat(s.browser.get(read).body()).isEqualTo(saved.body());
        assertThat(s.browser.request("PUT", s.path(), input(s.version.id(), 1, 7).toString(), "second-save").status()).isEqualTo(200);
        assertThat(s.browser.request("PUT", s.path(), body, "first-save").body()).isEqualTo(saved.body());
        assertThat(s.browser.request("PUT", s.path(), input(s.version.id(), 0, 8).toString(), "first-save").status()).isEqualTo(422);
        var second = artifacts.save(s.owner, s.project, 1, s.version.id(), source());
        assertThat(s.browser.get(read).body().path("error").path("code").asText()).isEqualTo("STALE_APP_VERSION");
        assertThat(s.browser.request("PUT", s.path(), input(s.version.id(), 2, 9).toString(), "stale-save").body().path("error").path("code").asText()).isEqualTo("STALE_APP_VERSION");
        // A committed intent replays its original result, even after the current code pointer changes.
        assertThat(s.browser.request("PUT", s.path(), body, "first-save").body()).isEqualTo(saved.body());
        var current = s.browser.get(s.path() + "?versionId=" + second.id());
        assertThat(current.body().path("revision").asInt()).isEqualTo(2);
        assertThat(current.body().path("value").path("count").asInt()).isEqualTo(7);
    }

    @Test void rejectsOtherGuestsCsrfInvalidShapesAndOversizedUtf8() throws Exception {
        var s = setup();
        var other = new ApiBrowser(port); other.initialize();
        String read = s.path() + "?versionId=" + s.version.id();
        String valid = input(s.version.id(), 0, 1).toString();
        assertThat(other.get(read).status()).isEqualTo(404);
        assertThat(other.request("PUT", s.path(), valid, "save").status()).isEqualTo(404);
        assertThat(new ApiBrowser(port).get(read).status()).isEqualTo(401);
        assertThat(s.browser.request("PUT", s.path(), valid, "save", null).status()).isEqualTo(403);
        var extra = input(s.version.id(), 0, 1).put("actorId", s.owner.toString());
        var big = input(s.version.id(), 0, 1); big.putObject("value").put("big", "界".repeat(90_000));
        var array = input(s.version.id(), 0, 1); array.putArray("value");
        for (var invalid : List.of(extra, big, array, input(s.version.id(), -1, 1),
                input(s.version.id(), 0, 1).put("expectedRevision", 0.5),
                input(s.version.id(), 0, 1).put("expectedRevision", 9007199254740992L),
                input(s.version.id(), 0, 1).put("stateSchemaVersion", 2))) {
            assertThat(s.browser.request("PUT", s.path(), invalid.toString(), UUID.randomUUID().toString()).status()).isEqualTo(422);
        }
        assertThat(s.browser.request("PUT", s.path(), valid, null).status()).isEqualTo(422);
        assertThat(s.browser.get(read).body().path("revision").asInt()).isZero();
    }

    @Test void concurrentFirstAndSubsequentSavesHaveExactlyOneWinner() throws Exception {
        var s = setup();
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int revision = 0; revision < 2; revision++) {
                var barrier = new CyclicBarrier(2);
                String body = input(s.version.id(), revision, revision + 1).toString();
                java.util.concurrent.Callable<ApiBrowser.Reply> save = () -> {
                    barrier.await();
                    return s.browser.request("PUT", s.path(), body, UUID.randomUUID().toString());
                };
                var a = pool.submit(save); var b = pool.submit(save);
                var responses = List.of(a.get(), b.get());
                assertThat(responses).extracting(ApiBrowser.Reply::status).containsExactlyInAnyOrder(200, 409);
                assertThat(responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().body().path("error").path("code").asText()).isEqualTo("STATE_CONFLICT");
            }
        } finally { pool.shutdownNow(); }
        assertThat(s.browser.get(s.path() + "?versionId=" + s.version.id()).body().path("revision").asInt()).isEqualTo(2);
    }
}
