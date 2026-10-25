package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Lifecycle wiring fault boundaries only; actual leases/PG/processes are exercised by RuntimeSchedulerIT. */
class ExecutionRuntimeCoordinatorTest {
    @Test void closeRetainsSharedResourcesUntilResidentAdmissionsAndWorkersHaveTerminated() throws Exception {
        try (var leases = mockConstruction(ExecutionCoordinatorLease.class, (lease, context) -> when(lease.healthy()).thenReturn(true));
             var residents = mockConstruction(ExecutionRuntimeDispatcher.class)) {
            var resources = mock(ExecutionCoordinator.WorkerResources.class);
            when(resources.runtime(any())).thenReturn(mock(ExecutionRuntimeResources.class));
            var coordinator = coordinator(resources); coordinator.start();
            assertThat(residents.constructed()).hasSize(1); var resident = residents.constructed().get(0);
            try {
                assertThat(coordinator.isRunning()).isTrue();
                assertThatThrownBy(coordinator::close).isInstanceOf(dev.atoms.demo.common.ApiException.class);
                verify(resources, never()).close(); verify(leases.constructed().get(0), never()).close();
                verify(resident).requestStop();
            } finally {
                when(resident.isTerminated()).thenReturn(true); when(resident.awaitTermination(any())).thenReturn(true);
                coordinator.close();
            }
            verify(resources).close(); verify(leases.constructed().get(0)).close();
        }
    }

    @Test void uncertainRuntimeObservationStopsTheCommonCoordinatorInsteadOfContinuingBuildDispatch() throws Exception {
        try (var leases = mockConstruction(ExecutionCoordinatorLease.class, (lease, context) -> when(lease.healthy()).thenReturn(true));
             var residents = mockConstruction(ExecutionRuntimeDispatcher.class, (resident, context) -> {
                 doThrow(new IOException("RUN_DISPATCH_UNAVAILABLE")).when(resident).checkOwnedRuns();
                 when(resident.isTerminated()).thenReturn(true); when(resident.awaitTermination(any())).thenReturn(true);
             })) {
            var resources = mock(ExecutionCoordinator.WorkerResources.class);
            when(resources.runtime(any())).thenReturn(mock(ExecutionRuntimeResources.class));
            var coordinator = coordinator(resources);
            try {
                coordinator.start();
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (coordinator.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(coordinator.isRunning()).isFalse();
                verify(residents.constructed().get(0), atLeastOnce()).checkOwnedRuns();
            } finally { coordinator.close(); }
            verify(residents.constructed().get(0)).requestStop(); verify(resources).close();
        }
    }

    private ExecutionCoordinator coordinator(ExecutionCoordinator.WorkerResources resources) {
        var settings = mock(ExecutionSettings.class); when(settings.enabled()).thenReturn(true);
        Path path = Path.of("/unused/runtime/coordinator");
        when(settings.load()).thenReturn(new ExecutionSettings.Configuration(path, path, path, path, path, path,
                Set.of(UUID.randomUUID()), 1, 8, 4, Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofMinutes(2)));
        return new ExecutionCoordinator(settings, mock(ExecutionBuildRepository.class), mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class), mock(DataSource.class), () -> true, () -> resources);
    }
}
