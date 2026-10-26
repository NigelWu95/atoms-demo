package dev.atoms.demo.projectexecution;

import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Read-only observer and one session-owned gate on the already approved A database. Never repairs or changes business rows. */
final class ExecutionMigrationFailureProbe implements AutoCloseable {
    static final long GATE = 718395247L;
    static final String SQL = """
            CREATE TABLE generated_app.execution_failed_migration_probe (id integer PRIMARY KEY);
            SELECT pg_catalog.pg_advisory_xact_lock(718395247);
            SELECT 1 / 0;
            """;
    record Snapshot(ExecutionMigrationHistory.State history, List<String> todos) { Snapshot { todos = List.copyOf(todos); } }
    private final Connection database;
    private final ExecutionPrivateConfig configuration;
    private boolean held;

    ExecutionMigrationFailureProbe(ExecutionBindingRepository.Target expected) throws Exception {
        assertThat(expected.project()).isEqualTo(ExecutionBindingContractIT.A);
        Path directory = Path.of(System.getProperty("execution.test-config-directory")).toRealPath();
        configuration = ExecutionPrivateConfig.readBusiness(directory.resolve(ExecutionBindingContractIT.A_DB + ".properties"),
                List.of(Path.of(System.getProperty("execution.runtime-fixture-root")), Path.of(System.getProperty("execution.application-directory"))));
        database = DriverManager.getConnection(configuration.jdbcUrl(), configuration.inspectionProperties(5));
        try {
            database.setReadOnly(true); database.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); database.setAutoCommit(false);
            var identity = ExecutionDatabaseInspection.inspect(database, configuration, 3);
            assertThat(identity.databaseOid()).isEqualTo(expected.databaseOid()); assertThat(identity.roleOid()).isEqualTo(expected.roleOid());
            assertThat(configuration.bindingId()).isEqualTo(expected.id());
        } catch (Exception | Error failure) { database.close(); throw failure; }
        finally { if (!database.isClosed()) { database.rollback(); database.setAutoCommit(true); } }
    }

    Snapshot snapshot() throws Exception {
        configuration.verify(); database.setAutoCommit(false);
        try (var statement = database.createStatement()) {
            statement.setQueryTimeout(3);
            var history = ExecutionMigrationHistory.read(database, ExecutionApplicationFixture.budget());
            try (var rows = statement.executeQuery("SELECT pg_catalog.to_regclass('generated_app.execution_failed_migration_probe') IS NULL")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue();
            }
            var todos = new ArrayList<String>();
            try (var rows = statement.executeQuery("SELECT pg_catalog.row_to_json(t)::text FROM generated_app.todos t ORDER BY id LIMIT 1001")) {
                while (rows.next()) todos.add(rows.getString(1));
            }
            assertThat(todos.size()).isLessThanOrEqualTo(1000); return new Snapshot(history, todos);
        } finally { database.rollback(); database.setAutoCommit(true); }
    }

    void holdGate() throws Exception {
        assertThat(held).isFalse();
        try (var statement = database.createStatement()) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(" + GATE + ")")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue(); held = true;
            }
        }
    }
    void awaitMigrationAtGate() throws Exception {
        assertThat(held).isTrue(); long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        do {
            try (var statement = database.createStatement()) {
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery("""
                        SELECT count(*) FROM pg_catalog.pg_stat_activity
                        WHERE datname=current_database() AND usename=current_user
                          AND pg_catalog.pg_backend_pid()=ANY(pg_catalog.pg_blocking_pids(pid))
                          AND position('pg_advisory_xact_lock(718395247)' in query)>0
                        """)) { assertThat(rows.next()).isTrue(); if (rows.getInt(1) == 1) return; }
            }
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        throw new AssertionError("Original application never reached the reviewed failing migration gate");
    }
    void releaseGate() throws Exception {
        if (!held) return;
        try (var statement = database.createStatement()) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery("SELECT pg_catalog.pg_advisory_unlock(" + GATE + ")")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue(); held = false;
            }
        }
    }
    @Override public void close() throws Exception {
        try { releaseGate(); } finally { database.close(); }
    }
}
