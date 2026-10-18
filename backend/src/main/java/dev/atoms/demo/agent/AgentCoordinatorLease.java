package dev.atoms.demo.agent;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** One dispatcher across the database; independent of how many tasks that dispatcher may run. */
final class AgentCoordinatorLease implements AutoCloseable {
    private final Connection connection;
    private final int backend;
    private boolean closed;
    AgentCoordinatorLease(DataSource database) throws SQLException { this(database.getConnection()); }
    AgentCoordinatorLease(Connection connection) throws SQLException {
        this.connection = connection;
        try (var statement = connection.createStatement()) {
            if (!connection.getAutoCommit()) throw new SQLException("Agent coordinator requires an independent connection");
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery("SELECT pg_try_advisory_lock(718395202),pg_backend_pid()")) {
                if (!rows.next() || !rows.getBoolean(1)) throw new SQLException("Agent coordinator already owned");
                backend = rows.getInt(2);
            }
        } catch (SQLException error) {
            // A timeout may follow successful acquisition: do not return an uncertain locked session to the pool.
            try { connection.abort(Runnable::run); } finally { connection.close(); }
            throw error;
        }
    }
    synchronized boolean healthy() {
        if (closed) return false;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (var rows = statement.executeQuery("""
                    SELECT pg_backend_pid(), EXISTS(SELECT 1 FROM pg_locks
                      WHERE locktype='advisory' AND pid=pg_backend_pid() AND classid=0 AND objid=718395202 AND objsubid=1 AND granted)
                    """)) {
                return rows.next() && rows.getInt(1)==backend && rows.getBoolean(2);
            }
        } catch (SQLException error) { return false; }
    }
    @Override public synchronized void close() throws SQLException {
        if (closed) return;
        closed = true;
        if (connection.isClosed()) return;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("SELECT pg_advisory_unlock(718395202)");
        } catch (SQLException error) {
            connection.abort(Runnable::run); throw error;
        } finally { connection.close(); }
    }
}
