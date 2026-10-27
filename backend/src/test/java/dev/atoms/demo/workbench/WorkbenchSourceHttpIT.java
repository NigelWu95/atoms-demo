package dev.atoms.demo.workbench;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.agent.ProjectAgentInitialization;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.projectexecution.ExecutionQuery;
import dev.atoms.demo.source.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class WorkbenchSourceHttpIT {
    @LocalServerPort int port;
    @Autowired ProjectService projects;
    @MockitoBean ExecutionQuery execution;
    @MockitoBean ProjectSourceWorkbenchGateway source;
    @MockitoBean ProjectAgentInitialization initialization;
    ApiBrowser browser; UUID owner, project, version; String path;
    @BeforeEach void setup() throws Exception {
        browser = new ApiBrowser(port); owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        project = projects.create(owner, "workbench-http").id(); version = UUID.randomUUID();
        path = "/api/projects/" + project + "/workbench";
        when(source.versions(owner, project, null, 30)).thenReturn(new ProjectSourceWorkbenchGateway.Versions(List.of(), null));
    }
    @Test void realOwnershipAndSessionPrecedeSourceReads() throws Exception {
        var own = browser.request("GET", path + "/source/versions", null, null);
        assertThat(own.status()).isEqualTo(200); assertThat(own.raw().headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        verify(execution).authorizeProject(owner, project); verify(source).versions(owner, project, null, 30);
        clearInvocations(source, execution);
        var other = new ApiBrowser(port); other.initialize();
        assertThat(other.request("GET", path + "/source/versions", null, null).status()).isEqualTo(404);
        assertThat(new ApiBrowser(port).request("GET", path + "/source/versions", null, null).status()).isEqualTo(401);
        verifyNoInteractions(source, execution);
    }
    @Test void rejectsUnknownDuplicateAndNoncanonicalInputsBeforeProviders() throws Exception {
        assertThat(browser.request("GET", path + "/source/versions?limit=1&limit=2", null, null).status()).isEqualTo(422);
        assertThat(browser.request("GET", path + "/source/versions?root=/tmp", null, null).status()).isEqualTo(422);
        assertThat(browser.request("GET", path.replace(project.toString(), "1-1-1-1-1") + "/capabilities", null, null).status()).isEqualTo(422);
        assertThat(browser.request("POST", path + "/source/initialize", "{\"path\":\"/tmp\"}", "init").status()).isEqualTo(422);
        assertThat(browser.request("POST", path + "/source/initialize", "{}", null).status()).isEqualTo(422);
        assertThat(browser.request("POST", path + "/source/initialize", "{}", "init", null).status()).isEqualTo(403);
        verifyNoInteractions(initialization, source, execution);
    }
    @Test void initializationReturnsOnlyPersistedOperationAndArchiveKeepsReadAccess() throws Exception {
        var id = UUID.randomUUID(); var op = mock(SourceOperation.class); when(op.id()).thenReturn(id);
        when(initialization.initialize(owner, project, "init-once")).thenReturn(new ProjectSourceEditGateway.Submitted(op,
                java.util.concurrent.CompletableFuture.completedFuture(op)));
        when(source.operation(owner, project, id)).thenReturn(new ProjectSourceWorkbenchGateway.Operation(id, "INITIALIZE", "PREPARING", null, null, java.time.Instant.now(), null));
        var response = browser.request("POST", path + "/source/initialize", "{}", "init-once");
        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body().path("status").asText()).isEqualTo("PREPARING");
        assertThat(response.body().toString()).doesNotContain("executionId", "templateDigest", "completion", "ownerId");
        projects.archive(owner, project.toString(), true);
        assertThat(browser.request("GET", path + "/capabilities", null, null).body().path("archived").asBoolean()).isTrue();
        assertThat(browser.request("GET", path + "/source/versions", null, null).status()).isEqualTo(200);
    }
}
