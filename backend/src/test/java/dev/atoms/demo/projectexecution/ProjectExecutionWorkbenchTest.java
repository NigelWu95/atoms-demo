package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProjectExecutionWorkbenchTest {
    @Test void usesOriginalCoordinatorAndKeepsUncertainAdmissionSeparateFromReady() throws Exception {
        var authorization = mock(ExecutionQuery.class); var coordinator = mock(ExecutionCoordinator.class);
        when(coordinator.isRunning()).thenReturn(true);
        var gateway = new ProjectExecutionWorkbench(authorization, mock(JdbcTemplate.class), mock(PlatformTransactionManager.class),
                (Supplier<ExecutionCoordinator>) () -> coordinator);
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), build = UUID.randomUUID(), binding = UUID.randomUUID();
        var pending = new CompletableFuture<ExecutionRun>();
        when(coordinator.startApplication(owner, project, build, binding, null, "same-key")).thenReturn(pending);
        var response = gateway.start(owner, project, new ProjectExecutionWorkbench.Start(build, binding, null, null), "same-key");
        assertThat(response.toCompletableFuture()).isNotDone();
        pending.completeExceptionally(new IOException("/private/config/secret"));
        assertThatThrownBy(() -> response.toCompletableFuture().join()).hasCauseInstanceOf(ApiException.class)
                .hasMessageNotContaining("/private/config/secret");
        verify(coordinator).startApplication(owner, project, build, binding, null, "same-key");
        verify(coordinator, never()).accessScope();
    }
    @Test void noCoordinatorOrUnauthorizedOwnerCannotCreateRuntimeResources() {
        var authorization = mock(ExecutionQuery.class); var coordinator = mock(ExecutionCoordinator.class);
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
        var gateway = new ProjectExecutionWorkbench(authorization, mock(JdbcTemplate.class), mock(PlatformTransactionManager.class),
                (Supplier<ExecutionCoordinator>) () -> coordinator);
        assertThatThrownBy(() -> gateway.stop(owner, project, UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("RUN_COORDINATOR_UNAVAILABLE"));
        clearInvocations(coordinator);
        doThrow(new ApiException(403, "EXECUTION_FORBIDDEN", "private")).when(authorization).authorizeProject(owner, project);
        assertThatThrownBy(() -> gateway.migration(owner, project, UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(403));
        verifyNoInteractions(coordinator);
    }
}
