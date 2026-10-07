package dev.atoms.demo.appdata;

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
class PublicStateIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ArtifactService artifacts;
    record Setup(ApiBrowser creator, UUID owner, String project, ArtifactService.Version version, String slug) {
        String path() { return "/api/public/apps/" + slug + "/state"; }
        String read() { return path() + "?versionId=" + version.id(); }
        String value(int count, int revision) { return "{\"loadedVersionId\":\"" + version.id() + "\",\"expectedRevision\":" + revision + ",\"stateSchemaVersion\":1,\"value\":{\"count\":" + count + "}}"; }
    }
    Setup setup() throws Exception {
        var creator = new ApiBrowser(port); UUID owner = UUID.fromString(creator.initialize().body().path("userId").asText());
        String project = creator.request("POST", "/api/projects", "{\"name\":\"公开数据隔离\"}", UUID.randomUUID().toString()).body().path("id").asText();
        var source = json.readTree("""
                {"formatVersion":1,"manifest":{"title":"数据应用","summary":"测试数据","runtime":"web-static-v1","stateSchemaVersion":1,"initialState":{"count":0}},
                "files":{"view.html":"<main>应用</main>","styles.css":"","main.js":"Atoms.ready(() => {});"}}
                """);
        var version = artifacts.save(owner, project, 0, null, source);
        return new Setup(creator, owner, project, version, publish(creator, project, version, 0));
    }
    String publish(ApiBrowser creator, String project, ArtifactService.Version version, long revision) throws Exception {
        assertThat(creator.request("POST", "/api/projects/" + project + "/versions/" + version.id() + "/preview-reports",
                "{\"status\":\"PASSED\",\"checks\":[\"SCRIPT_PARSE\",\"SDK_STARTUP\",\"RUNTIME_ERRORS\"],\"errors\":[],\"viewport\":{\"width\":1200,\"height\":900}}", UUID.randomUUID().toString()).status()).isEqualTo(201);
        var result = creator.request("PUT", "/api/projects/" + project + "/publication",
                "{\"versionId\":\"" + version.id() + "\",\"expectedProjectRevision\":" + version.versionNo() + ",\"expectedPublicationRevision\":" + revision + "}", UUID.randomUUID().toString());
        assertThat(result.status()).isEqualTo(200);
        return result.body().path("slug").asText();
    }
    @Test void isolatesLiveVisitorsProjectsAndPreviewWhilePreservingIntentsAndConflicts() throws Exception {
        var s = setup(); var visitor = new ApiBrowser(port); visitor.initialize(); var other = new ApiBrowser(port); other.initialize();
        assertThat(new ApiBrowser(port).get(s.read()).status()).isEqualTo(401);
        assertThat(s.creator.request("PUT", "/api/projects/" + s.project + "/preview-state", s.value(99, 0), "preview").status()).isEqualTo(200);
        assertThat(s.creator.get(s.read()).body().path("value").path("count").asInt()).isZero();
        assertThat(visitor.get(s.read()).status()).isEqualTo(200);
        var saved = visitor.request("PUT", s.path(), s.value(7, 0), "live");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(visitor.request("PUT", s.path(), s.value(7, 0), "live").body()).isEqualTo(saved.body());
        assertThat(visitor.request("PUT", s.path(), s.value(8, 0), "live").status()).isEqualTo(422);
        assertThat(visitor.request("PUT", s.path(), s.value(8, 0), "conflict").status()).isEqualTo(409);
        assertThat(visitor.request("PUT", s.path(), s.value(8, 1), "csrf", null).status()).isEqualTo(403);
        assertThat(visitor.request("PUT", s.path(), s.value(8, 1).replace("\"value\":", "\"actorId\":\"spoof\",\"value\":"), "actor").status()).isEqualTo(422);
        assertThat(visitor.get(s.read()).body().path("value").path("count").asInt()).isEqualTo(7);
        assertThat(other.get(s.read()).body().path("revision").asInt()).isZero();
        assertThat(s.creator.get("/api/projects/" + s.project + "/preview-state?versionId=" + s.version.id()).body().path("value").path("count").asInt()).isEqualTo(99);
        var otherProject = setup();
        assertThat(visitor.get(otherProject.read()).body().path("revision").asInt()).isZero();
    }
    @Test void updatesKeepDataOldPagesCannotWriteAndOfflineRejectsEvenSuccessfulReplays() throws Exception {
        var s = setup(); var visitor = new ApiBrowser(port); visitor.initialize();
        assertThat(visitor.request("PUT", s.path(), s.value(7, 0), "live").status()).isEqualTo(200);
        var version = artifacts.save(s.owner, s.project, 1, s.version.id(), json.valueToTree(s.version.artifact()));
        publish(s.creator, s.project, version, 1);
        assertThat(visitor.get(s.read()).status()).isEqualTo(409);
        assertThat(visitor.request("PUT", s.path(), s.value(8, 1), "old-version").status()).isEqualTo(409);
        assertThat(visitor.request("PUT", s.path(), s.value(7, 0), "live").status()).isEqualTo(200);
        String current = s.path() + "?versionId=" + version.id();
        assertThat(visitor.get(current).body().path("value").path("count").asInt()).isEqualTo(7);
        assertThat(s.creator.request("DELETE", "/api/projects/" + s.project + "/publication?expectedPublicationRevision=2", null, "offline").status()).isEqualTo(204);
        assertThat(visitor.get(current).status()).isEqualTo(404);
        assertThat(visitor.request("PUT", s.path(), s.value(7, 0), "live").status()).isEqualTo(404);
        publish(s.creator, s.project, version, 3);
        assertThat(visitor.get(current).body().path("value").path("count").asInt()).isEqualTo(7);
    }
}
