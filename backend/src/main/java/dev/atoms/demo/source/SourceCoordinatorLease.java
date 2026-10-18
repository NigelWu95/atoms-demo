package dev.atoms.demo.source;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Database-wide ownership complements the filesystem lease, including differently configured roots. */
final class SourceCoordinatorLease implements AutoCloseable {
    private final Connection connection;
    private final int backend;
    private boolean closed;
    SourceCoordinatorLease(DataSource source) throws SQLException { this(source.getConnection()); }
    SourceCoordinatorLease(Connection connection) throws SQLException {
        this.connection = connection;
        try (var statement = connection.createStatement()) {
            if (!connection.getAutoCommit()) throw new SQLException("Source coordinator requires an independent connection");
            statement.setQueryTimeout(3);
            try (var result = statement.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(718395105),pg_catalog.pg_backend_pid()")) {
                if (!result.next() || !result.getBoolean(1)) throw new SQLException("Source coordinator is already owned");
                backend = result.getInt(2);
            }
        } catch (SQLException error) {
            // A timeout could occur after the server acquired the lock. Never return an uncertain locked session to a pool.
            try { connection.abort(Runnable::run); } finally { connection.close(); }
            throw error;
        }
    }
    synchronized boolean healthy() {
        if (closed) return false;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (var result = statement.executeQuery("""
                    SELECT pg_catalog.pg_backend_pid(), EXISTS(SELECT 1 FROM pg_catalog.pg_locks
                        WHERE locktype='advisory' AND pid=pg_catalog.pg_backend_pid() AND classid=0 AND objid=718395105 AND objsubid=1 AND granted)
                    """)) {
                return result.next() && result.getInt(1) == backend && result.getBoolean(2);
            }
        } catch (SQLException error) { return false; }
    }
    @Override public synchronized void close() throws SQLException {
        if (closed) return;
        closed = true;
        if (connection.isClosed()) return;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("SELECT pg_catalog.pg_advisory_unlock(718395105)");
        } catch (SQLException error) {
            connection.abort(Runnable::run);
            throw error;
        } finally { connection.close(); }
    }
}
