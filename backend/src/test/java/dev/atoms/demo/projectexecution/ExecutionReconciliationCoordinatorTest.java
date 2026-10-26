package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Coordinator lifecycle boundaries only. Native receipts and actual business migration failures have separate contracts. */
class ExecutionReconciliationCoordinatorTest {
    final UUID owner = UUID.randomUUID();
    final MockEnvironment environment = new MockEnvironment();
    final AtomicBoolean sourceReady = new AtomicBoolean(true);
    final ExecutionCoordinator.WorkerResources resources = mock(ExecutionCoordinator.WorkerResources.class);
    final ExecutionRun failed = mock(ExecutionRun.class);

    @Test void maintenanceScopePinsResourcesUntilCommittedAcceptanceAndClose() throws Exception {
        try (var leases = mockConstruction(ExecutionCoordinatorLease.class, (lease, context) -> when(lease.healthy()).thenReturn(true));
             var residents = mockConstruction(ExecutionRuntimeDispatcher.class)) {
            var coordinator = coordinator(); coordinator.start(); var resident = residents.constructed().get(0);
            try {
                assertThatThrownBy(() -> coordinator.open(failed)).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                assertThatThrownBy(coordinator::close).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                try (var scope = coordinator.open(failed)) {
                    var artifact = mock(ExecutionArtifact.class); var build = mock(ExecutionBuild.class);
                    var budget = ExecutionApplicationFixture.budget();
                    when(resources.artifact(build, "jar", "manifest", budget)).thenReturn(artifact);
                    assertThat(scope.artifact(build, "jar", "manifest", budget)).isSameAs(artifact);
                    clearInvocations(resources);
                    TransactionSynchronizationManager.setActualTransactionActive(true);
                    try { scope.checkAuthority(); }
                    finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
                    verify(resources, never()).check(); // No filesystem scans in the metadata transaction.
                    doAnswer(call -> {
                        when(resident.isTerminated()).thenReturn(true); when(resident.awaitTermination(any())).thenReturn(true); return null;
                    }).when(resident).acceptStoppedFailure(eq(failed), any());
                    var audit = mock(ExecutionRunReconciliationRepository.Audit.class);
                    coordinator.accepted(failed, audit);
                    verify(resident).acceptStoppedFailure(failed, audit);
                    assertThatThrownBy(coordinator::close).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                    verify(resources, never()).close(); verify(leases.constructed().get(0), never()).close();
                }
                coordinator.close(); verify(resources).close(); verify(leases.constructed().get(0)).close();
                assertThat(coordinator.isRunning()).isFalse();
                coordinator.accepted(failed, mock(ExecutionRunReconciliationRepository.Audit.class));
                verify(resident, times(1)).acceptStoppedFailure(eq(failed), any());
            } finally {
                when(resident.isTerminated()).thenReturn(true); when(resident.awaitTermination(any())).thenReturn(true); coordinator.close();
            }
        }
    }

    @Test void lostLeaseOrSourceAuthorityCannotOpenOrReleaseAStoppedJob() throws Exception {
        try (var leases = mockConstruction(ExecutionCoordinatorLease.class, (lease, context) -> when(lease.healthy()).thenReturn(true));
             var residents = mockConstruction(ExecutionRuntimeDispatcher.class)) {
            var coordinator = coordinator(); coordinator.start(); var resident = residents.constructed().get(0);
            try {
                assertThatThrownBy(coordinator::close).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                try (var scope = coordinator.open(failed)) {
                    sourceReady.set(false);
                    assertThatThrownBy(scope::checkAuthority).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                    assertThatThrownBy(() -> coordinator.open(failed)).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                    sourceReady.set(true); when(leases.constructed().get(0).healthy()).thenReturn(false);
                    assertThatThrownBy(scope::checkAuthority).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                    assertThatThrownBy(() -> coordinator.accepted(failed, mock(ExecutionRunReconciliationRepository.Audit.class)))
                            .isInstanceOf(dev.atoms.demo.common.ApiException.class);
                    verify(resident, never()).acceptStoppedFailure(any(), any()); verify(resources, never()).close();
                }
            } finally {
                when(resident.isTerminated()).thenReturn(true); when(resident.awaitTermination(any())).thenReturn(true); coordinator.close();
            }
        }
    }

    private ExecutionCoordinator coordinator() throws IOException {
        environment.withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + key, "/unused/maintenance/" + key);
        when(failed.ownerId()).thenReturn(owner);
        when(resources.runtime(any())).thenReturn(mock(ExecutionRuntimeResources.class));
        return new ExecutionCoordinator(new ExecutionSettings(environment), mock(ExecutionBuildRepository.class), mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class), mock(DataSource.class), sourceReady::get, () -> resources);
    }
}
