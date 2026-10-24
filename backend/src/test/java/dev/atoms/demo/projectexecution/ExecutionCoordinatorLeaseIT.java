package dev.atoms.demo.projectexecution;

import java.sql.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionCoordinatorLeaseIT {
    @Autowired HikariDataSource database;

    @BeforeEach void target() throws Exception {
        try (var connection = database.getConnection(); var query = connection.createStatement()) {
            query.setQueryTimeout(3);
            try (var rows = query.executeQuery("SELECT current_database()")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("atoms_demo_test");
            }
        }
    }

    @Test void onlyOneSessionOwnsDispatchAndCloseAllowsANewOwner() throws Exception {
        try (var first = new ExecutionCoordinatorLease(database)) {
            assertThat(first.healthy()).isTrue();
            assertThatThrownBy(() -> new ExecutionCoordinatorLease(database))
                    .isInstanceOf(SQLException.class).hasMessage("EXECUTION_COORDINATOR_UNAVAILABLE").hasNoCause();
            assertThat(first.healthy()).isTrue();
        }
        try (var next = new ExecutionCoordinatorLease(database)) { assertThat(next.healthy()).isTrue(); }
    }

    @Test void losingAndReacquiringTheLockDoesNotReviveTheOldCapability() throws Exception {
        try (var connection = database.getConnection(); var lease = new ExecutionCoordinatorLease(connection, database::evictConnection)) {
            assertThat(lease.healthy()).isTrue();
            try (var query = connection.createStatement()) { query.execute("SELECT pg_catalog.pg_advisory_unlock(718395204)"); }
            assertThat(lease.healthy()).isFalse();
            try (var query = connection.createStatement()) { query.execute("SELECT pg_catalog.pg_advisory_lock(718395204)"); }
            assertThat(lease.healthy()).isFalse();
        }
        try (var next = new ExecutionCoordinatorLease(database)) { assertThat(next.healthy()).isTrue(); }
    }

    @Test void lossOfOurPhysicalConnectionStopsOwnershipAndDoesNotAffectAnotherSession() throws Exception {
        try (var connection = database.getConnection(); var lease = new ExecutionCoordinatorLease(connection, database::evictConnection);
             var other = database.getConnection()) {
            assertThat(lease.healthy()).isTrue();
            connection.abort(Runnable::run); // Only the connection created and owned by this fixture.
            assertThat(lease.healthy()).isFalse(); assertThat(other.isValid(2)).isTrue();
        }
        try (var next = new ExecutionCoordinatorLease(database)) { assertThat(next.healthy()).isTrue(); }
    }

    @Test void sourceAgentAndBothBuildSystemsKeepTheirIndependentLockNamespace() throws Exception {
        try (var other = database.getConnection()) {
            try {
                try (var query = other.createStatement()) {
                    query.setQueryTimeout(3);
                    // These are this fixture's connections/locks, never released by another backend PID.
                    for (int id : new int[]{718395105, 718395201, 718395202, 718395203, 1748392063, 1748392064})
                        try (var rows = query.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(" + id + ")")) {
                            assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue();
                        }
                }
                try (var lease = new ExecutionCoordinatorLease(database)) { assertThat(lease.healthy()).isTrue(); }
                try (var query = other.createStatement(); var rows = query.executeQuery("""
                        SELECT count(*) FROM pg_catalog.pg_locks WHERE locktype='advisory'
                          AND pid=pg_catalog.pg_backend_pid() AND granted
                        """)) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(6);
                }
            } finally { other.abort(Runnable::run); database.evictConnection(other); }
        }
    }
}
