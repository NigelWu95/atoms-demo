package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal metadata only. A Target is an inspection snapshot, never authority to receive credentials or launch. */
final class ExecutionBindingRepository {
    private final JdbcTemplate jdbc;
    ExecutionBindingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Target(UUID project, UUID id, int databasePort, String database, String role, int port, long databaseOid, long roleOid) {
        Target {
            Objects.requireNonNull(project); Objects.requireNonNull(id);
            if (database == null || !database.matches("[a-z][a-z0-9_]{0,62}") || role == null || !role.matches("[a-z][a-z0-9_]{0,62}")
                    || databasePort < 1 || databasePort > 65535 || port < 1024 || port > 65535 || port == databasePort
                    || databaseOid < 1 || databaseOid > 4294967295L || roleOid < 1 || roleOid > 4294967295L)
                throw new IllegalArgumentException("Invalid runtime database identity");
        }
        String hash() {
            String value = "atoms-runtime-binding-v1\n" + project + "\n" + id + "\n" + databasePort + "\n"
                    + database + "\n" + role + "\n" + port + "\n" + databaseOid + "\n" + roleOid;
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    record Binding(Target target, int slot, Instant createdAt) {}

    Binding get(UUID project) {
        return jdbc.query("SELECT * FROM execution_database_bindings WHERE project_id=?", (rs, n) -> {
            var target = new Target(rs.getObject("project_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("database_port"),
                    rs.getString("database_name"), rs.getString("role_name"), rs.getInt("application_port"), rs.getLong("database_oid"), rs.getLong("role_oid"));
            if (!target.hash().equals(rs.getString("identity_sha256"))) throw unavailable();
            return new Binding(target, rs.getInt("binding_slot"), rs.getTimestamp("created_at").toInstant());
        }, project).stream().findFirst().orElse(null);
    }

    /** Caller performs authorization and real DB/file inspection outside this short metadata transaction. */
    Binding reserve(Target target, int capacity) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Binding reservation requires a transaction");
        if (capacity < 1 || capacity > 4) throw new IllegalArgumentException("Invalid binding capacity");
        try {
            jdbc.execute("SET LOCAL lock_timeout = '3s'");
            jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, target.project());
            // Project -> binding capacity; distinct from Agent and build coordinator/capacity locks.
            // https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
            jdbc.execute("SELECT pg_catalog.pg_advisory_xact_lock(718395205)");
            var existing = get(target.project());
            if (existing != null) {
                if (!existing.target().equals(target)) throw conflict();
                return existing;
            }
            var slots = jdbc.queryForList("""
                    SELECT slot FROM generate_series(1,4) slot
                    WHERE (SELECT count(*) FROM execution_database_bindings) < ?
                        AND NOT EXISTS (SELECT FROM execution_database_bindings WHERE binding_slot=slot)
                    ORDER BY slot LIMIT 1
                    """, Integer.class, capacity);
            if (slots.isEmpty()) throw new ApiException(429, "RUN_DB_CAPACITY_FULL", "已达到业务数据库绑定容量。");
            jdbc.update("""
                    INSERT INTO execution_database_bindings(id,project_id,binding_slot,database_port,database_name,role_name,
                        application_port,database_oid,role_oid,identity_sha256) VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, target.id(), target.project(), slots.get(0), target.databasePort(), target.database(), target.role(),
                    target.port(), target.databaseOid(), target.roleOid(), target.hash());
            return get(target.project());
        } catch (DuplicateKeyException error) { throw conflict(); }
        catch (DataAccessException error) { throw unavailable(); }
    }
    private static ApiException conflict() { return new ApiException(409, "RUN_DB_BINDING_CONFLICT", "项目的业务库、角色或端口绑定不一致。"); }
    private static ApiException unavailable() { return new ApiException(503, "RUN_DB_STATE_UNAVAILABLE", "业务数据库绑定状态暂不可用。"); }
}
