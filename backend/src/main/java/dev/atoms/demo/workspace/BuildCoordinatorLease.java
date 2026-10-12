package dev.atoms.demo.workspace;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Database-wide coordinator ownership, in addition to the workspace-root OS lock. */
final class BuildCoordinatorLease implements AutoCloseable {
    private final Connection connection;
    private final int backend;
    private boolean closed;
    BuildCoordinatorLease(DataSource source) throws SQLException {
        connection=source.getConnection();
        try (var statement=connection.createStatement()) {
            if (!connection.getAutoCommit()) throw new SQLException("Coordinator requires an independent connection");
            statement.setQueryTimeout(3);
            try(var result=statement.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(1748392064),pg_catalog.pg_backend_pid()")) {
                result.next();
                if(!result.getBoolean(1)) throw new SQLException("Build coordinator already owned");
                backend=result.getInt(2);
            }
        } catch(SQLException error) { connection.close(); throw error; }
    }
    synchronized boolean healthy() {
        if(closed) return false;
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            try(var result=statement.executeQuery("SELECT pg_catalog.pg_backend_pid()")) { return result.next() && result.getInt(1)==backend; }
        } catch(SQLException error) { return false; }
    }
    @Override public synchronized void close() throws SQLException {
        if(closed) return; closed=true;
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("SELECT pg_catalog.pg_advisory_unlock(1748392064)");
        } finally { connection.close(); }
    }
}
