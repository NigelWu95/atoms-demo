package dev.atoms.demo.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.model.ModelGateway;
import dev.atoms.demo.model.ModelException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"GENERATION_MAX_MODEL_CALLS=100000", "atoms.generation.worker-enabled=false"}, classes = {dev.atoms.demo.AtomsDemoApplication.class, GenerationExecutionIT.Fakes.class})
@ActiveProfiles("test")
class GenerationExecutionIT {
    @LocalServerPort int port;
    @Autowired RunExecutionService execution;
    @Autowired GenerationWorker worker;
    @Autowired RunRepository runs;
    @Autowired RunAdmissionService admission;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ArtifactService artifacts;
    @Autowired FakeModel model;
    @TestConfiguration static class Fakes { @Bean @Primary FakeModel fakeModel() { return new FakeModel(); } }
    static class FakeModel implements ModelGateway {
        final AtomicInteger calls = new AtomicInteger();
        boolean fail;
        Runnable duringBuild = () -> {};
        public boolean configured() { return true; }
        public Answer complete(String system, String user, BooleanSupplier cancelled) {
            calls.incrementAndGet();
            if (fail) throw new ModelException("MODEL_AUTH_FAILED", "模型认证失败。");
            String content;
            if (system.contains("You plan")) content = "{\"title\":\"测试应用\",\"goal\":\"测试事务\",\"features\":[\"计数\"],\"preserve\":[],\"dataNeeded\":true,\"steps\":[\"构建\"]}";
            else {
                duringBuild.run();
                content = "{\"formatVersion\":1,\"manifest\":{\"title\":\"测试应用\",\"summary\":\"仅集成测试替身\",\"runtime\":\"web-static-v1\",\"stateSchemaVersion\":1,\"initialState\":{\"count\":0}},\"files\":{\"view.html\":\"<main>测试应用</main>\",\"styles.css\":\"\",\"main.js\":\"Atoms.ready(async (sdk) => { await sdk.storage.get(); });\"}}";
            }
            return new Answer(content, new Usage(10L, 20L, 30L));
        }
    }
    record Setup(ApiBrowser browser, UUID owner, String project) {}
    Setup setup() throws Exception {
        model.fail = false; model.duringBuild = () -> {};
        var browser = new ApiBrowser(port); UUID owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        String project = browser.request("POST", "/api/projects", "{\"name\":\"工作流集成测试\"}", UUID.randomUUID().toString()).body().path("id").asText();
        return new Setup(browser, owner, project);
    }
    Run enqueue(Setup s, UUID base, long revision) throws Exception {
        var input = json.createObjectNode().put("prompt", "创建或修改测试应用").put("intent", base == null ? "CREATE" : "MODIFY").put("expectedProjectRevision", revision);
        if (base == null) input.putNull("baseVersionId"); else input.put("baseVersionId", base.toString());
        var response = s.browser.request("POST", "/api/projects/" + s.project + "/runs", input.toString(), UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(202);
        return execution.start(s.owner, response.body().path("runId").asText());
    }
    @Test void createsAndModifiesRealVersionsPreservingApplicationState() throws Exception {
        var s = setup(); Run first = enqueue(s, null, 0);
        worker.process(first);
        Run result = runs.get(s.owner, first.id().toString());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.lastEventSeq()).isGreaterThan(3);
        assertThat(artifacts.get(s.owner, s.project, result.resultVersionId().toString()).versionNo()).isEqualTo(1);
        String value = "{\"loadedVersionId\":\"" + result.resultVersionId() + "\",\"expectedRevision\":0,\"stateSchemaVersion\":1,\"value\":{\"count\":7}}";
        assertThat(s.browser.request("PUT", "/api/projects/" + s.project + "/preview-state", value, "save").status()).isEqualTo(200);
        Run modify = enqueue(s, result.resultVersionId(), 1); worker.process(modify);
        var changed = runs.get(s.owner, modify.id().toString());
        assertThat(changed.status()).isEqualTo("SUCCEEDED");
        assertThat(s.browser.get("/api/projects/" + s.project + "/preview-state?versionId=" + changed.resultVersionId()).body().path("value").path("count").asInt()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_calls WHERE run_id = ? AND status = 'SUCCEEDED'", Integer.class, first.id())).isEqualTo(2);
        assertThat(runs.messages(s.owner, s.project, null, 20).items()).hasSize(6);
    }
    @Test void cancellationDuringProviderCallPreventsLateVersionCommit() throws Exception {
        var s = setup(); Run run = enqueue(s, null, 0);
        model.duringBuild = () -> admission.cancel(s.owner, run.id().toString());
        worker.process(run);
        assertThat(runs.get(s.owner, run.id().toString()).status()).isEqualTo("CANCELLED");
        assertThat(artifacts.list(s.owner, s.project, null, 20).items()).isEmpty();
        assertThat(s.browser.get("/api/projects/" + s.project).body().path("activeRunId").isNull()).isTrue();
    }
    @Test void providerFailureHasTerminalStateAndDoesNotRetry() throws Exception {
        var s = setup(); Run run = enqueue(s, null, 0); model.fail = true;
        int before = model.calls.get(); worker.process(run);
        var result = runs.get(s.owner, run.id().toString());
        assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.errorCode()).isEqualTo("MODEL_AUTH_FAILED");
        assertThat(model.calls.get()).isEqualTo(before + 1);
        assertThat(artifacts.list(s.owner, s.project, null, 20).items()).isEmpty();
    }
}
