package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionStoppedRunMaintenanceTest {
    @TempDir Path temporary;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID(), execution = UUID.randomUUID();
    final String hash = "a".repeat(64);
    final AtomicBoolean sourceReady = new AtomicBoolean(true);
    HikariDataSource database;
    Connection connection;
    ResultSet rows;
    MockEnvironment environment;
    ExecutionSettings settings;
    Path root;

    @BeforeEach void setup() throws Exception {
        root = temporary.toRealPath().resolve("execution");
        try (var paths = new ExecutionPaths(root, List.of())) { assertThat(paths.checkedRoot()).isEqualTo(root); }
        environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", root.toString());
        for (String key : List.of("node", "npm", "java", "maven", "policy"))
            environment.withProperty("atoms.project-execution." + key, temporary.resolve(key).toString());
        settings = new ExecutionSettings(environment);
        database = mock(HikariDataSource.class); connection = mock(Connection.class);
        var statement = mock(Statement.class); rows = mock(ResultSet.class);
        when(database.unwrap(HikariDataSource.class)).thenReturn(database); when(database.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true); when(rows.getInt(1)).thenReturn(42); when(rows.getBoolean(2)).thenReturn(true);
    }

    @Test void exactStoppedRunCanBeInspectedWhileBothLeasesStayHeldUntilExplicitClose() throws Exception {
        var failed = failed();
        var maintenance = acquire();
        try {
            try (var scope = maintenance.open(failed)) {
                scope.checkAuthority();
                assertThatThrownBy(() -> maintenance.open(failed)).isInstanceOf(IOException.class);
                assertThatThrownBy(maintenance::close).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> ExecutionPaths.existing(root, List.of())).isInstanceOf(IOException.class);
            }
            maintenance.accepted(failed, audit(failed, hash));
            maintenance.accepted(failed, audit(failed, hash));
            verify(connection, never()).close();
        } finally { maintenance.close(); }
        verify(connection).close(); maintenance.close();
        assertThatThrownBy(() -> maintenance.open(failed)).isInstanceOf(IOException.class);
        try (var reopened = ExecutionPaths.existing(root, List.of())) { assertThat(reopened.checkedRoot()).isEqualTo(root); }
    }

    @Test void unknownExecutionMissingStopProofWrongTargetAndTerminalRunsAreRejected() throws Exception {
        try (var maintenance = acquire()) {
            var rejected = new ArrayList<ExecutionRun>();
            for (String change : List.of("owner", "project", "run", "status", "pending", "execution", "proof", "ready", "finished")) {
                var row = mock(ExecutionRun.class);
                when(row.ownerId()).thenReturn(change.equals("owner") ? UUID.randomUUID() : owner);
                when(row.projectId()).thenReturn(change.equals("project") ? UUID.randomUUID() : project);
                when(row.id()).thenReturn(change.equals("run") ? UUID.randomUUID() : run);
                when(row.status()).thenReturn(change.equals("status") ? "STOPPED" : "NEEDS_RECONCILIATION");
                when(row.executionPending()).thenReturn(change.equals("pending"));
                when(row.executionId()).thenReturn(change.equals("execution") ? null : execution);
                when(row.stoppedSha256()).thenReturn(change.equals("proof") ? null : hash);
                when(row.readyAt()).thenReturn(change.equals("ready") ? Instant.now() : null);
                when(row.finishedAt()).thenReturn(change.equals("finished") ? Instant.now() : null);
                when(row.errorCode()).thenReturn("RUN_START_TIMEOUT"); rejected.add(row);
            }
            for (var row : rejected) assertThatThrownBy(() -> maintenance.open(row)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> maintenance.accepted(failed(), audit(failed(), "b".repeat(64)))).isInstanceOf(IOException.class);
        }
    }

    @Test void absentRootIsNeverCreatedAndFailedFileLockAcquisitionReleasesOnlyItsDatabaseLease() throws Exception {
        Path missing = temporary.resolve("missing"); environment.withProperty("atoms.project-execution.root", missing.toString());
        assertThatThrownBy(this::acquire).hasMessage("RUN_RECONCILIATION_UNAVAILABLE");
        assertThat(Files.exists(missing)).isFalse(); verify(connection).close();
        clearInvocations(connection);
        environment.withProperty("atoms.project-execution.root", root.toString());
        try (var prior = ExecutionPaths.existing(root, List.of())) {
            assertThatThrownBy(this::acquire).hasMessage("RUN_RECONCILIATION_UNAVAILABLE");
            verify(connection).close(); assertThat(prior.checkedRoot()).isEqualTo(root);
        }
    }

    @Test void databaseContentionDoesNotOpenTheExecutionRoot() throws Exception {
        when(rows.getBoolean(2)).thenReturn(false);
        assertThatThrownBy(this::acquire).hasMessage("RUN_RECONCILIATION_UNAVAILABLE").hasNoCause();
        verify(connection).abort(any()); verify(database).evictConnection(connection);
        try (var paths = ExecutionPaths.existing(root, List.of())) { assertThat(paths.checkedRoot()).isEqualTo(root); }
    }

    @Test void unavailableSourceOrUnauthorizedMaintainerDoesNotAcquireAnyLease() {
        sourceReady.set(false); assertThatThrownBy(this::acquire).isInstanceOf(IOException.class);
        sourceReady.set(true); environment.withProperty("atoms.project-execution.maintainer-ids", UUID.randomUUID().toString());
        assertThatThrownBy(this::acquire).isInstanceOf(IOException.class); verifyNoInteractions(database);
    }

    @Test void configurationAndLeaseChangesRevokeExistingScopes() throws Exception {
        try (var maintenance = acquire(); var scope = maintenance.open(failed())) {
            environment.withProperty("atoms.project-execution.max-running-projects", "3");
            assertThatThrownBy(scope::checkAuthority).isInstanceOf(IOException.class);
            environment.withProperty("atoms.project-execution.max-running-projects", "4");
            when(rows.getInt(1)).thenReturn(43);
            assertThatThrownBy(scope::checkAuthority).isInstanceOf(IOException.class);
            when(rows.getInt(1)).thenReturn(42);
            assertThatThrownBy(scope::checkAuthority).isInstanceOf(IOException.class);
        }
    }

    @Test void ambientTransactionsAreRejectedBeforeResourceAcquisition() {
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(this::acquire).hasMessage("EXECUTION_TRANSACTION_ACTIVE");
            verifyNoInteractions(database);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    private ExecutionStoppedRunMaintenance acquire() throws IOException {
        return ExecutionStoppedRunMaintenance.acquire(settings, database, sourceReady::get, List.of(), owner, project, run);
    }
    private ExecutionRun failed() {
        return new ExecutionRun(run, project, owner, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                hash, hash, hash, hash, hash, hash, "NEEDS_RECONCILIATION", false, execution, false, hash,
                "RUN_START_TIMEOUT", Instant.now(), null, null);
    }
    private ExecutionRunReconciliationRepository.Audit audit(ExecutionRun failed, String proof) {
        return new ExecutionRunReconciliationRepository.Audit(UUID.randomUUID(), run, project, owner, execution,
                proof, hash, hash, hash, Instant.now());
    }
}
