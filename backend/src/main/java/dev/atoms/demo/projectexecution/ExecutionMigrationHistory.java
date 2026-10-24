package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Fixed, bounded SELECTs on the maintainer's runtime binding. No Flyway migrate/validate/repair/clean invocation. */
final class ExecutionMigrationHistory {
    record State(boolean exists, long objects, long tableOid, List<ExecutionMigrationPolicy.Applied> applied, String sha256) {
        State { applied = List.copyOf(applied); }
    }
    record Snapshot(ExecutionBindingRepository.Target binding, State state) {}

    static Snapshot inspect(UUID project, ExecutionPrivateConfig config, OwnedProcess.Budget budget) throws IOException {
        ExecutionMigrations.check(budget); config.applicationPort(); config.verify();
        try (var db = DriverManager.getConnection(config.jdbcUrl(), config.inspectionProperties(seconds(budget)))) {
            db.setReadOnly(true); db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); db.setAutoCommit(false);
            try {
                var identity = ExecutionDatabaseInspection.inspect(db, config, seconds(budget));
                var state = read(db, budget);
                config.verify(); ExecutionMigrations.check(budget);
                var binding = new ExecutionBindingRepository.Target(project, config.bindingId(), config.databasePort(), config.database(),
                        config.role(), config.applicationPort(), identity.databaseOid(), identity.roleOid());
                return new Snapshot(binding, state);
            } finally { db.rollback(); }
        } catch (SQLException error) { throw new IOException("RUN_MIGRATION_DB_UNAVAILABLE"); }
        catch (IOException error) {
            String code = Objects.toString(error.getMessage(), "");
            throw new IOException(Set.of("RUN_MIGRATION_HISTORY_INVALID", "EXECUTION_TIMEOUT", "EXECUTION_DB_TARGET_REJECTED",
                    "EXECUTION_DB_CONFIGURATION_INVALID").contains(code) ? code : "RUN_MIGRATION_DB_UNAVAILABLE");
        }
    }

    /** Also exercised against rolled-back SQL fixtures; the production entry above owns the read-only transaction. */
    static State read(Connection db, OwnedProcess.Budget budget) throws SQLException, IOException {
        long objects;
        try (var query = prepare(db, """
                SELECT (SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='generated_app')
                  + (SELECT count(*) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='generated_app')
                  + (SELECT count(*) FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
                       WHERE n.nspname='generated_app' AND t.typrelid=0 AND t.typelem=0)
                """, budget); var row = query.executeQuery()) { row.next(); objects = row.getLong(1); }
        long oid = 0;
        try (var query = prepare(db, """
                SELECT c.oid,c.relkind,c.relrowsecurity,c.relowner=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user) AS owned,
                  EXISTS(SELECT FROM pg_catalog.pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid) AS inherited
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='generated_app' AND c.relname='flyway_schema_history'
                """, budget); var row = query.executeQuery()) {
            if (row.next()) {
                if (!"r".equals(row.getString("relkind")) || row.getBoolean("relrowsecurity") || !row.getBoolean("owned") || row.getBoolean("inherited")) throw invalid();
                oid = row.getLong("oid");
            }
        }
        var rows = new ArrayList<ExecutionMigrationPolicy.Applied>();
        var bytes = new ByteArrayOutputStream(); var digest = new DataOutputStream(bytes);
        digest.writeUTF("execution-migration-history-v1"); digest.writeLong(objects); digest.writeLong(oid);
        if (oid != 0) {
            shape(db, oid, budget);
            try (var query = prepare(db, """
                    SELECT installed_rank,substring(version,1,10) AS version,substring(description,1,101) AS description,
                      substring(type,1,21) AS type,substring(script,1,117) AS script,checksum,
                      substring(installed_by,1,101) AS installed_by,installed_on,execution_time,success,
                      (version IS NULL OR length(version)<=9) AND length(description)<=100 AND length(type)<=20
                        AND length(script)<=116 AND length(installed_by)<=100 AS bounded
                    FROM generated_app.flyway_schema_history ORDER BY installed_rank LIMIT 129
                    """, budget); var row = query.executeQuery()) {
                while (row.next()) {
                    ExecutionMigrations.check(budget);
                    if (rows.size() >= ExecutionMigrations.MAX_FILES || !row.getBoolean("bounded") || row.getInt("execution_time") < 0) throw invalid();
                    var applied = new ExecutionMigrationPolicy.Applied(row.getInt("installed_rank"), row.getString("version"), row.getString("description"),
                            row.getString("type"), row.getString("script"), row.getObject("checksum", Integer.class), row.getBoolean("success"));
                    rows.add(applied);
                    digest.writeInt(applied.rank()); field(digest, applied.version()); field(digest, applied.description()); field(digest, applied.type());
                    field(digest, applied.script()); field(digest, Objects.toString(applied.checksum(), null)); digest.writeBoolean(applied.success());
                    field(digest, row.getString("installed_by")); field(digest, row.getTimestamp("installed_on").toLocalDateTime().toString());
                    digest.writeInt(row.getInt("execution_time"));
                }
            }
        }
        ExecutionMigrations.check(budget);
        return new State(oid != 0, objects, oid, rows, ExecutionMigrations.hash(bytes.toByteArray()));
    }
    private static void shape(Connection db, long oid, OwnedProcess.Budget budget) throws SQLException, IOException {
        // Flyway 11.7.2 PostgreSQL history uses these ten built-in types. Reject domains, views, RLS and extra columns.
        var types = Map.of("installed_rank",23, "version",1043, "description",1043, "type",1043, "script",1043,
                "checksum",23, "installed_by",1043, "installed_on",1114, "execution_time",23, "success",16);
        var seen = new HashSet<String>();
        try (var query = prepare(db, "SELECT attname,atttypid,attnotnull FROM pg_catalog.pg_attribute WHERE attrelid=? AND attnum>0 AND NOT attisdropped", budget)) {
            query.setLong(1,oid);
            try (var row = query.executeQuery()) {
                while (row.next()) {
                    String name = row.getString(1);
                    if (!Objects.equals(types.get(name), row.getInt(2)) || !seen.add(name)
                            || !Set.of("version","checksum").contains(name) && !row.getBoolean(3)) throw invalid();
                }
            }
        }
        if (!seen.equals(types.keySet())) throw invalid();
    }
    private static PreparedStatement prepare(Connection db, String sql, OwnedProcess.Budget budget) throws SQLException, IOException {
        int seconds = seconds(budget); var query = db.prepareStatement(sql);
        try { query.setQueryTimeout(seconds); query.setMaxRows(129); return query; }
        catch (SQLException error) { query.close(); throw error; }
    }
    private static int seconds(OwnedProcess.Budget budget) throws IOException {
        ExecutionMigrations.check(budget); long seconds = budget.remaining().toSeconds();
        if (seconds < 1) throw new IOException("EXECUTION_TIMEOUT");
        return (int) Math.min(5,seconds);
    }
    private static void field(DataOutputStream digest, String value) throws IOException {
        digest.writeBoolean(value != null); if (value != null) digest.writeUTF(value);
    }
    private static IOException invalid() { return new IOException("RUN_MIGRATION_HISTORY_INVALID"); }
}
