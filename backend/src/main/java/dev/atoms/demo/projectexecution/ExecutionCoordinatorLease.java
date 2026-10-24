package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database-wide dispatch ownership, independent of the execution-root lease and worker-slot locks. */
final class ExecutionCoordinatorLease implements AutoCloseable {
    private final Connection connection;
    private final Consumer<Connection> evict;
    private final int backend;
    private boolean lost, closed;

    ExecutionCoordinatorLease(DataSource database) throws SQLException { this(pool(database)); }

    ExecutionCoordinatorLease(HikariDataSource pool) throws SQLException {
        this(borrow(pool), pool::evictConnection);
    }

    /** Transfers this private connection to the lease; callers must not return it to a pool themselves. */
    ExecutionCoordinatorLease(Connection connection, Consumer<Connection> evict) throws SQLException {
        this.connection = connection; this.evict = java.util.Objects.requireNonNull(evict);
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive() || !connection.getAutoCommit()) throw unavailable();
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                // Session locks survive transaction completion; this dedicated connection owns exactly one acquisition.
                // https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
                try (var rows = statement.executeQuery("SELECT pg_catalog.pg_backend_pid(),pg_catalog.pg_try_advisory_lock(718395204)")) {
                    if (!rows.next() || !rows.getBoolean(2) || rows.getInt(1) <= 0) throw unavailable();
                    backend = rows.getInt(1);
                }
            }
        } catch (SQLException | RuntimeException error) {
            lost = true; discard(); throw unavailable();
        }
    }

    synchronized boolean healthy() {
        if (closed || lost) return false;
        try {
            if (connection.isClosed() || !connection.getAutoCommit()) throw unavailable();
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(2);
                try (var rows = statement.executeQuery("""
                        SELECT pg_catalog.pg_backend_pid(), EXISTS(SELECT 1 FROM pg_catalog.pg_locks
                          WHERE locktype='advisory' AND pid=pg_catalog.pg_backend_pid()
                            AND classid=0 AND objid=718395204 AND objsubid=1 AND granted)
                        """)) {
                    if (rows.next() && rows.getInt(1) == backend && rows.getBoolean(2)) return true;
                }
            }
        } catch (SQLException | RuntimeException error) { /* A failed observation permanently revokes this capability. */ }
        lost = true;
        return false;
    }

    @Override public synchronized void close() throws SQLException {
        if (closed) return;
        if (lost) { discard(); return; }
        try {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(2);
                try (var rows = statement.executeQuery("SELECT pg_catalog.pg_backend_pid(),pg_catalog.pg_advisory_unlock(718395204)")) {
                    if (!rows.next() || rows.getInt(1) != backend || !rows.getBoolean(2)) throw unavailable();
                }
            }
            connection.close(); closed = true;
        } catch (SQLException | RuntimeException error) {
            lost = true; discard(); throw unavailable();
        }
    }

    private void discard() throws SQLException {
        try {
            // abort terminates the physical session before close may return a pooled wrapper.
            // If abort fails, retain the checked-out connection; never optimistically return an unknown lock.
            // https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Connection.html#abort(java.util.concurrent.Executor)
            connection.abort(Runnable::run);
            // Hikari close() resets physical state even after abort; mark this pool entry evicted first.
            // https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-6.3.3/src/main/java/com/zaxxer/hikari/HikariDataSource.java
            evict.accept(connection);
            connection.close(); closed = true;
        } catch (SQLException | RuntimeException error) { throw unavailable(); }
    }

    private static Connection borrow(DataSource database) throws SQLException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try { return database.getConnection(); }
        catch (SQLException | RuntimeException error) { throw unavailable(); }
    }
    private static HikariDataSource pool(DataSource database) throws SQLException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            var pool = database.unwrap(HikariDataSource.class);
            if (pool == null) throw unavailable();
            return pool;
        } catch (SQLException | RuntimeException error) { throw unavailable(); }
    }
    private static SQLException unavailable() { return new SQLException("EXECUTION_COORDINATOR_UNAVAILABLE"); }
}
