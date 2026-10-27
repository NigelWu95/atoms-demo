package dev.atoms.demo.workbench;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.agent.*;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.projectexecution.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "atoms.project-workbench.enabled=true")
class WorkbenchCommandsHttpIT {
    @LocalServerPort int port;
    @Autowired ProjectService projects;
    @MockitoBean ExecutionQuery queries;
    @MockitoBean ProjectExecutionService builds;
    @MockitoBean ProjectAgentService agent;
    @MockitoBean AgentQuery agentQuery;
    @MockitoBean ProjectExecutionWorkbench runtime;
    ApiBrowser browser; UUID owner, project, version, build, binding; String path;
    @BeforeEach void setup() throws Exception {
        browser = new ApiBrowser(port); owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        project = projects.create(owner, "workbench-commands").id(); version = UUID.randomUUID(); build = UUID.randomUUID(); binding = UUID.randomUUID();
        path = "/api/projects/" + project + "/workbench";
    }
    @Test void submitsExactSourceAndKeyWithoutAcceptingShellOrOtherOptions() throws Exception {
        when(builds.submit(owner, project, version, "build-once")).thenReturn(new ProjectExecutionService.BuildView(build, project, version,
                "a".repeat(64), "QUEUED", false, null, java.time.Instant.now(), null, 0));
        var result = browser.request("POST", path + "/builds", "{\"sourceVersionId\":\"" + version + "\"}", "build-once");
        assertThat(result.status()).isEqualTo(202); assertThat(result.body().path("sourceVersionId").asText()).isEqualTo(version.toString());
        verify(builds).submit(owner, project, version, "build-once"); clearInvocations(builds, agent);
        assertThat(browser.request("POST", path + "/builds", "{\"sourceVersionId\":\"" + version + "\",\"command\":\"npm install\"}", "other").status()).isEqualTo(422);
        assertThat(browser.request("POST", path + "/agent/tasks", "{\"prompt\":\"edit\",\"baseSourceVersionId\":\"" + version + "\",\"expectedSourceRevision\":1.5}", "edit").status()).isEqualTo(422);
        verifyNoInteractions(builds, agent);
    }
    @Test void startUsesAsyncAdmissionAndReturnsStartingRatherThanReady() throws Exception {
        UUID run = UUID.randomUUID();
        var task = new ExecutionQuery.TaskView(run, project, ExecutionQuery.Kind.RUN, version, build, null, "STARTING", false, false, false, null, java.time.Instant.now(), null);
        var command = new ProjectExecutionWorkbench.Start(build, binding, null, null);
        when(runtime.start(owner, project, command, "run-once")).thenReturn(CompletableFuture.completedFuture(task));
        String body = "{\"buildId\":\"" + build + "\",\"bindingId\":\"" + binding + "\"}";
        var result = browser.request("POST", path + "/runs", body, "run-once");
        assertThat(result.status()).isEqualTo(202); assertThat(result.body().path("status").asText()).isEqualTo("STARTING");
        assertThat(result.body().toString()).doesNotContain("port", "executionId", "bindingIdentitySha256");
        verify(runtime).start(owner, project, command, "run-once");
    }
    @Test void ownershipCsrfAndInputValidationGuardEveryCommandFamily() throws Exception {
        var stranger = new ApiBrowser(port); stranger.initialize();
        for (String suffix : List.of("/builds/" + build + "/cancel", "/agent/tasks/" + build + "/cancel", "/runs/" + build + "/stop")) {
            assertThat(stranger.request("POST", path + suffix, "{}", "cancel").status()).isEqualTo(404);
            assertThat(browser.request("POST", path + suffix, "{}", "cancel", null).status()).isEqualTo(403);
        }
        assertThat(browser.request("POST", path + "/runs", "{\"port\":1234}", "invalid").status()).isEqualTo(422);
        assertThat(browser.request("POST", path + "/builds/" + build + "/migration-approvals", "{\"approved\":true}", "invalid").status()).isEqualTo(422);
        verifyNoInteractions(runtime, agent, builds);
    }
}
