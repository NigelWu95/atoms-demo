package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import dev.atoms.demo.source.ProjectSourceRestorationGateway;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** HTTP authorization/validation only; actual CAS, idempotency and Git publication have separate integration tests. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.project-delivery.enabled=true", "atoms.project-delivery.origin=http://127.0.0.1:5174"})
class DeliveryRestorationIT {
    @LocalServerPort int port;
    @MockitoBean ProjectExecutionAccess execution;
    @MockitoBean ProjectSourceRestorationGateway source;
    private final UUID project = UUID.randomUUID(), target = UUID.randomUUID(), expected = UUID.randomUUID(), operation = UUID.randomUUID();
    private final String base = "/api/projects/" + project + "/delivery";
    private final String body = "{\"targetSourceVersionId\":\"" + target + "\",\"expectedSourceVersionId\":\"" + expected + "\",\"expectedRevision\":7}";
    private ApiBrowser browser;
    private UUID owner;

    @BeforeEach void setup() throws Exception {
        browser = new ApiBrowser(port); owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        doAnswer(call -> {
            if (!owner.equals(call.getArgument(0)) || !project.equals(call.getArgument(1))) throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不可访问。");
            return null;
        }).when(execution).authorizeProject(any(), any(), any());
        when(source.head(owner, project)).thenReturn(new ProjectSourceRestorationGateway.Head(project, expected, 7, "READY"));
        var result = new ProjectSourceRestorationGateway.Operation(operation, project, expected, 7, target, "PREPARING", null, null, Instant.EPOCH, null);
        when(source.restore(owner, project, target, expected, 7, "restore-request")).thenReturn(result);
        when(source.operation(owner, project, operation)).thenReturn(result);
    }

    @Test void exposesWhitelistedHeadAcceptedOperationAndPollingThroughExistingSession() throws Exception {
        var head = browser.get(base + "/source-head");
        assertThat(head.status()).isEqualTo(200); assertThat(head.body().path("revision").asLong()).isEqualTo(7);
        var accepted = browser.request("POST", base + "/restorations", body, "restore-request");
        assertThat(accepted.status()).isEqualTo(202); assertThat(accepted.body().path("operationId").asText()).isEqualTo(operation.toString());
        assertThat(accepted.raw().headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var polled = browser.get(base + "/operations/" + operation);
        assertThat(polled.status()).isEqualTo(200); assertThat(polled.body()).isEqualTo(accepted.body());
        assertThat(polled.raw().body()).doesNotContain("executionId", "candidateSha256", "ownerId", "idempotencySha256");
        verify(execution, never()).running(any(), any(), any(), any());
        verify(execution, never()).build(any(), any(), any(), any());
    }

    @Test void csrfAuthenticationAndOwnershipStillProtectAllManagementRoutes() throws Exception {
        assertThat(browser.request("POST", base + "/restorations", body, "restore-request", null).status()).isEqualTo(403);
        var anonymous = new ApiBrowser(port); anonymous.refreshCsrf();
        assertThat(anonymous.request("POST", base + "/restorations", body, "restore-request").status()).isEqualTo(401);
        assertThat(anonymous.get(base + "/source-head").status()).isEqualTo(401);
        verifyNoInteractions(source, execution);
        var stranger = new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.request("POST", base + "/restorations", body, "restore-request").status()).isEqualTo(404);
        assertThat(stranger.get(base + "/operations/" + operation).status()).isEqualTo(404);
        verifyNoInteractions(source);
    }

    @Test void rejectsNoncanonicalIdsExtraFieldsMissingKeyAndNonIntegerRevisionsBeforeAdmission() throws Exception {
        for (var invalid : new String[]{"{}", "[]", body.replace("7}", "0}"), body.replace("7}", "7.0}"),
                body.replace("7}", "9223372036854775808}"), body.replace("7}", "7,\"url\":\"ignored\"}"), body.replace(target.toString(), "1-1-1-1-1")})
            assertThat(browser.request("POST", base + "/restorations", invalid, "restore-request").status()).isEqualTo(422);
        assertThat(browser.request("POST", base + "/restorations", body, null).status()).isEqualTo(422);
        assertThat(browser.request("POST", base + "/restorations?latest=true", body, "restore-request").status()).isEqualTo(422);
        assertThat(browser.get(base + "/source-head?path=anything").status()).isEqualTo(422);
        verifyNoInteractions(source, execution);
    }

    @Test void preservesSourceConflictAndFailedOperationWithoutReportingSuccessfulRestore() throws Exception {
        when(source.restore(owner, project, target, expected, 7, "restore-request"))
                .thenThrow(new ApiException(409, "SOURCE_BASE_CONFLICT", "源码已更新。"));
        var conflict = browser.request("POST", base + "/restorations", body, "restore-request");
        assertThat(conflict.status()).isEqualTo(409); assertThat(conflict.body().path("error").path("code").asText()).isEqualTo("SOURCE_BASE_CONFLICT");
        when(source.operation(owner, project, operation)).thenReturn(new ProjectSourceRestorationGateway.Operation(operation, project, expected,
                7, target, "NEEDS_RECONCILIATION", null, "SOURCE_EXECUTION_UNCERTAIN", Instant.EPOCH, Instant.now()));
        var result = browser.get(base + "/operations/" + operation);
        assertThat(result.status()).isEqualTo(200); assertThat(result.body().path("status").asText()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(result.body().path("resultSourceVersionId").isNull()).isTrue();
    }
}
