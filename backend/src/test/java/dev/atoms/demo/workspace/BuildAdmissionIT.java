package dev.atoms.demo.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")
class BuildAdmissionIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BuildExecutionEnvironment execution;
    private final List<UUID> owners = new ArrayList<>();
    @BeforeEach void ready() { when(execution.admissionFailure()).thenReturn(null); }
    @AfterEach void settleFixtures() {
        for (UUID owner : owners) jdbc.update("UPDATE engineering_builds SET status='CANCELLED',finished_at=now(),error_code='BUILD_CANCELLED' WHERE owner_id=? AND status='QUEUED'", owner);
    }
    @Test void replaysOneAcceptedBuildEvenAfterEnvironmentBecomesUnavailable() throws Exception {
        var f = fixture(); String key = UUID.randomUUID().toString();
        var accepted = f.browser.request("POST", f.path, f.body, key);
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.body().properties()).hasSize(3);
        when(execution.admissionFailure()).thenReturn(new ApiException(503,"BUILD_DISABLED","未启用"));
        assertThat(f.browser.request("POST",f.path,f.body,key).body()).isEqualTo(accepted.body());
        assertThat(f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString()).body().path("error").path("code").asText()).isEqualTo("BUILD_DISABLED");
        assertThat(f.browser.request("POST",f.path,"{\"versionId\":\""+UUID.randomUUID()+"\"}",key).body().path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }
    @Test void refusesUnknownFieldsCsrfAndOtherOwnersBeforeAdmission() throws Exception {
        var f = fixture(); var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.request("POST",f.path,f.body,UUID.randomUUID().toString()).status()).isEqualTo(404);
        assertThat(new ApiBrowser(port).request("POST",f.path,f.body,UUID.randomUUID().toString()).status()).isIn(401,403);
        assertThat(f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString(),"wrong-csrf").status()).isEqualTo(403);
        assertThat(f.browser.request("POST",f.path,"{\"versionId\":\""+f.version+"\",\"command\":\"secret-canary\"}",UUID.randomUUID().toString()).status()).isEqualTo(422);
        assertThat(f.browser.request("POST",f.path,f.body,null).status()).isEqualTo(422);
        verify(execution,never()).settings();
    }
    @Test void concurrentSameIntentReturnsOneIdAndDifferentIntentHitsProjectLimit() throws Exception {
        var f = fixture(); String key = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(6);
        try {
            var tasks = java.util.stream.IntStream.range(0,6).<java.util.concurrent.Callable<ApiBrowser.Reply>>mapToObj(i -> () -> f.browser.request("POST",f.path,f.body,key)).toList();
            var replies = pool.invokeAll(tasks).stream().map(result -> { try { return result.get(); } catch (Exception e) { throw new AssertionError(e); } }).toList();
            assertThat(replies).allMatch(reply -> reply.status()==202);
            assertThat(replies.stream().map(reply -> reply.body().path("buildId").asText()).distinct()).hasSize(1);
            assertThat(f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString()).body().path("error").path("code").asText()).isEqualTo("BUILD_ALREADY_ACTIVE");
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentDifferentProjectsCannotExceedFourActiveBuilds() throws Exception {
        var fixtures = new ArrayList<Fixture>(); for (int i=0;i<8;i++) fixtures.add(fixture());
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = fixtures.stream().<java.util.concurrent.Callable<ApiBrowser.Reply>>map(f -> () -> f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString())).toList();
            var replies = pool.invokeAll(tasks).stream().map(result -> { try { return result.get(); } catch (Exception e) { throw new AssertionError(e); } }).toList();
            assertThat(replies.stream().filter(reply -> reply.status()==202)).hasSize(4);
            assertThat(replies.stream().filter(reply -> reply.status()!=202)).allMatch(reply -> reply.status()==503 && reply.body().path("error").path("code").asText().equals("BUILD_QUEUE_FULL"));
        } finally { pool.shutdownNow(); }
    }
    private Fixture fixture() throws Exception {
        var browser = new ApiBrowser(port); UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText()); owners.add(owner);
        String project = browser.request("POST","/api/projects","{\"name\":\"构建准入测试\"}",UUID.randomUUID().toString()).body().path("id").asText();
        var version = artifacts.save(owner,project,0,null,json.valueToTree(new Artifact(1,new Artifact.Manifest("构建测试","固定应用","web-static-v1",1,json.createObjectNode()),
                Map.of("view.html","<main>测试</main>","styles.css","body{color:black}","main.js","// fixture"))));
        return new Fixture(browser,"/api/projects/"+project+"/builds",version.id(),"{\"versionId\":\""+version.id()+"\"}");
    }
    private record Fixture(ApiBrowser browser,String path,UUID version,String body) {}
}
