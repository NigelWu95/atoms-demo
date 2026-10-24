package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.sql.*;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared catalog policy. Configuration parsing fixes the expected test/runtime purpose before any connection. */
final class ExecutionDatabaseInspection {
    /** Opens a fresh bounded READ ONLY transaction, runs only catalog SELECT, closes it before returning. */
    static Identity verify(ExecutionPrivateConfig config, OwnedProcess.Budget budget) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        int seconds = seconds(budget);
        config.verify();
        try (var connection = DriverManager.getConnection(config.jdbcUrl(), config.inspectionProperties(seconds))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                Identity result = inspect(connection, config, seconds(budget));
                config.verify();
                seconds(budget);
                return result;
            } finally { connection.rollback(); }
        } catch (SQLException error) { throw new IOException("EXECUTION_DB_UNAVAILABLE"); }
        catch (IOException error) {
            // A close/rollback failure can attach a driver exception to the original refusal.
            // Do not let suppressed connection details cross the internal module boundary.
            String code = error.getMessage();
            throw new IOException(Set.of("EXECUTION_TIMEOUT", "EXECUTION_DB_CONFIGURATION_INVALID", "EXECUTION_DB_TARGET_REJECTED")
                    .contains(code == null ? "" : code) ? code : "EXECUTION_DB_UNAVAILABLE");
        }
    }

    // PG 17 identity/ACL functions: https://www.postgresql.org/docs/17/functions-info.html
    // JDBC read-only transactions/timeouts: https://jdbc.postgresql.org/documentation/use/
    static Identity inspect(Connection connection, ExecutionPrivateConfig config, int seconds) throws SQLException, IOException {
        try (var statement = connection.prepareStatement("""
                SELECT d.oid AS database_oid, r.oid AS role_oid,
                    pg_catalog.current_database() AS database_name, current_user AS role_name, session_user AS session_name,
                    pg_catalog.shobj_description(d.oid,'pg_database') AS database_marker,
                    pg_catalog.shobj_description(r.oid,'pg_authid') AS role_marker,
                    pg_catalog.current_setting('transaction_read_only') = 'on' AS readonly,
                    NOT pg_catalog.pg_is_in_recovery() AND NOT d.datistemplate AND d.datallowconn AS writable_target,
                    r.rolcanlogin AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                        AND d.datdba <> r.oid
                        AND NOT EXISTS(SELECT FROM pg_catalog.pg_auth_members WHERE member=r.oid OR roleid=r.oid) AS limited_role,
                    NOT pg_catalog.has_database_privilege(d.oid,'CREATE')
                        AND NOT pg_catalog.has_database_privilege(d.oid,'TEMP')
                        AND NOT EXISTS(SELECT FROM pg_catalog.aclexplode(COALESCE(d.datacl,pg_catalog.acldefault('d',d.datdba)))
                            WHERE grantee=0) AS limited_database,
                    EXISTS(SELECT FROM pg_catalog.pg_namespace n WHERE n.nspname='generated_app' AND n.nspowner=r.oid
                        AND pg_catalog.has_schema_privilege(n.oid,'USAGE') AND pg_catalog.has_schema_privilege(n.oid,'CREATE')) AS owns_schema,
                    NOT EXISTS(SELECT FROM pg_catalog.pg_namespace n
                        WHERE n.nspname NOT IN ('generated_app','information_schema') AND pg_catalog.left(n.nspname,3) <> 'pg_'
                        AND (pg_catalog.has_schema_privilege(n.oid,'USAGE') OR pg_catalog.has_schema_privilege(n.oid,'CREATE'))) AS no_other_schema,
                    (SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname='generated_app') AS relation_count
                FROM pg_catalog.pg_database d CROSS JOIN pg_catalog.pg_roles r
                WHERE d.datname=pg_catalog.current_database() AND r.rolname=current_user
                """)) {
            statement.setQueryTimeout(seconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !config.database().equals(rows.getString("database_name"))
                        || !config.role().equals(rows.getString("role_name")) || !config.role().equals(rows.getString("session_name"))
                        || !config.marker().equals(rows.getString("database_marker")) || !config.marker().equals(rows.getString("role_marker"))
                        || !rows.getBoolean("readonly") || !rows.getBoolean("writable_target") || !rows.getBoolean("limited_role")
                        || !rows.getBoolean("limited_database") || !rows.getBoolean("owns_schema") || !rows.getBoolean("no_other_schema"))
                    throw new IOException("EXECUTION_DB_TARGET_REJECTED");
                return new Identity(rows.getLong("database_oid"), rows.getLong("role_oid"),
                        rows.getLong("relation_count"));
            }
        }
    }

    private static int seconds(OwnedProcess.Budget budget) throws IOException {
        try {
            long remaining = budget.remaining().toSeconds();
            if (Thread.currentThread().isInterrupted() || remaining < 1) throw new IOException();
            return (int) Math.min(5, remaining);
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_TIMEOUT"); }
    }

    record Identity(long databaseOid, long roleOid, long relationCount) {}
}
