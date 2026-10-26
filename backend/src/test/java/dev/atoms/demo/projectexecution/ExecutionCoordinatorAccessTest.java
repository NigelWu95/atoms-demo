package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Resource-lifetime contract only; AccessIT supplies real PG, sealed files and native processes. */
class ExecutionCoordinatorAccessTest {
    @Test void usesOnlyTheOriginalResourcesAndRevokesRetainedScopesOnClose() throws Exception {
        try (var leases = mockConstruction(ExecutionCoordinatorLease.class, (lease, context) -> when(lease.healthy()).thenReturn(true))) {
            var resources = mock(ExecutionCoordinator.WorkerResources.class);
            var coordinator = coordinator(resources);
            denied(coordinator::accessScope);
            coordinator.start();
            var build = mock(ExecutionBuild.class); var artifact = mock(ExecutionArtifact.class);
            var process = mock(ExecutionRunProcess.class); var run = UUID.randomUUID();
            OwnedProcess.Budget budget = () -> Duration.ofSeconds(10);
            when(resources.artifact(build, "jar", "manifest", budget)).thenReturn(artifact);
            when(resources.ownedProcess(run)).thenReturn(process);
            var scope = coordinator.accessScope();
            try {
                assertThat(scope.artifact(build, "jar", "manifest", budget)).isSameAs(artifact);
                assertThat(scope.ownedProcess(run)).isSameAs(process);
            } finally { coordinator.close(); }
            denied(scope::check); denied(() -> scope.ownedProcess(run));
            denied(() -> scope.artifact(build, "jar", "manifest", budget));
            verify(resources, times(1)).artifact(any(), any(), any(), any());
            verify(resources, times(1)).ownedProcess(any());
        }
    }

    @Test void refusesTransactionAndLostLeaseIncludingRevocationDuringARead() throws Exception {
        try (var leases = mockConstruction(ExecutionCoordinatorLease.class, (lease, context) -> when(lease.healthy()).thenReturn(true))) {
            var resources = mock(ExecutionCoordinator.WorkerResources.class);
            var coordinator = coordinator(resources); coordinator.start();
            try {
                var scope = coordinator.accessScope(); var run = UUID.randomUUID();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try { denied(scope::check); denied(() -> scope.ownedProcess(run)); }
                finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
                verify(resources, never()).ownedProcess(any());
                var lease = leases.constructed().get(0);
                when(resources.ownedProcess(run)).thenAnswer(invocation -> {
                    when(lease.healthy()).thenReturn(false); return mock(ExecutionRunProcess.class);
                });
                denied(() -> scope.ownedProcess(run)); denied(scope::check);
            } finally { coordinator.close(); }
        }
    }

    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("EXECUTION_COORDINATOR_UNAVAILABLE"));
    }
    private ExecutionCoordinator coordinator(ExecutionCoordinator.WorkerResources resources) {
        var settings = mock(ExecutionSettings.class); when(settings.enabled()).thenReturn(true);
        Path path = Path.of("/unused/access/coordinator");
        when(settings.load()).thenReturn(new ExecutionSettings.Configuration(path, path, path, path, path, path,
                Set.of(UUID.randomUUID()), 1, 8, 4, Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofMinutes(2)));
        return new ExecutionCoordinator(settings, mock(ExecutionBuildRepository.class), mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class), mock(DataSource.class), () -> true, () -> resources);
    }
}
