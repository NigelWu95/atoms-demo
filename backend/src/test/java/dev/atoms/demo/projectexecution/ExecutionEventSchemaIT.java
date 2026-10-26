package dev.atoms.demo.projectexecution;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Rolled-back metadata only: no tool, filesystem, business DB or process execution. */
@ActiveProfiles("test") @Transactional
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionEventSchemaIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    static final String HASH = "a".repeat(64);
    record Fixture(UUID owner, UUID project, UUID source) {}
    record Task(UUID id, String table, String events, String key, String flag) {}

    @BeforeEach void target() {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)).isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
    }
    @ParameterizedTest @ValueSource(strings = {"BUILD", "PREPARATION", "RUN"})
    void stateChangesHaveOrderedImmutableEventsAndNoOpWritesDoNotDuplicateThem(String kind) {
        var task = task(jdbc, seed(jdbc), kind);
        assertThat(count(task)).isEqualTo(1);
        jdbc.update("UPDATE " + task.table + " SET " + task.flag + "=true WHERE id=?", task.id);
        assertThat(count(task)).isEqualTo(2);
        jdbc.update("UPDATE " + task.table + " SET " + task.flag + "=true WHERE id=?", task.id);
        assertThat(count(task)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT seq FROM " + task.events + " WHERE " + task.key + "=? ORDER BY seq", Integer.class, task.id)).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT payload->>'status' FROM " + task.events + " WHERE " + task.key + "=? AND seq=1", String.class, task.id))
                .isEqualTo(kind.equals("RUN") ? "STARTING" : "QUEUED");
        fails(() -> jdbc.update("DELETE FROM " + task.events + " WHERE " + task.key + "=?", task.id));
        fails(() -> jdbc.update("UPDATE " + task.events + " SET payload='{}' WHERE " + task.key + "=?", task.id));
        fails(() -> append(task, 4, "{}"));
        assertThat(count(task)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"BUILD", "PREPARATION", "RUN"})
    void eventCountExhaustionRollsBackTheParentStateChange(String kind) {
        var task = task(jdbc, seed(jdbc), kind);
        jdbc.update("INSERT INTO " + task.events + "(" + task.key + ",seq,payload) SELECT ?,n,'{}'::jsonb FROM generate_series(2,1000) n", task.id);
        assertThat(count(task)).isEqualTo(1000);
        fails(() -> jdbc.update("UPDATE " + task.table + " SET " + task.flag + "=true WHERE id=?", task.id));
        assertThat(jdbc.queryForObject("SELECT " + task.flag + " FROM " + task.table + " WHERE id=?", Boolean.class, task.id)).isFalse();
        assertThat(count(task)).isEqualTo(1000);
        fails(() -> finish(jdbc, task));
        assertThat(jdbc.queryForObject("SELECT finished_at IS NULL FROM " + task.table + " WHERE id=?", Boolean.class, task.id)).isTrue();
        if (!kind.equals("RUN")) assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, task.id)).isEqualTo(1);
        if (kind.equals("PREPARATION")) assertThat(jdbc.queryForObject("SELECT status FROM source_edit_leases WHERE task_id=?", String.class, task.id)).isEqualTo("ACTIVE");
    }

    @ParameterizedTest @ValueSource(strings = {"BUILD", "PREPARATION", "RUN"})
    void totalBytesAndSingleEventLimitAreEnforcedEvenForDirectAppends(String kind) {
        var task = task(jdbc, seed(jdbc), kind);
        fails(() -> append(task, 2, "{\"pad\":\"" + "x".repeat(1024) + "\"}"));
        int overhead = jdbc.queryForObject("SELECT octet_length(jsonb_build_object('pad','')::text)", Integer.class);
        while (bytes(task) < 65536) {
            int size = (int) Math.min(1024, 65536 - bytes(task)); assertThat(size).isGreaterThanOrEqualTo(overhead);
            append(task, count(task) + 1, "{\"pad\":\"" + "x".repeat(size - overhead) + "\"}");
        }
        assertThat(bytes(task)).isEqualTo(65536);
        int before = count(task);
        fails(() -> jdbc.update("UPDATE " + task.table + " SET " + task.flag + "=true WHERE id=?", task.id));
        fails(() -> append(task, before + 1, "{}"));
        assertThat(jdbc.queryForObject("SELECT " + task.flag + " FROM " + task.table + " WHERE id=?", Boolean.class, task.id)).isFalse();
        assertThat(bytes(task)).isEqualTo(65536); assertThat(count(task)).isEqualTo(before);
    }
    private int count(Task task) { return jdbc.queryForObject("SELECT count(*) FROM " + task.events + " WHERE " + task.key + "=?", Integer.class, task.id); }
    private long bytes(Task task) { return jdbc.queryForObject("SELECT sum(octet_length(payload::text)) FROM " + task.events + " WHERE " + task.key + "=?", Long.class, task.id); }
    private void append(Task task, int seq, String payload) {
        jdbc.update("INSERT INTO " + task.events + "(" + task.key + ",seq,payload) VALUES (?,?,?::jsonb)", task.id, seq, payload);
    }
    private void fails(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var point = tx.createSavepoint();
            assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class);
            tx.rollbackToSavepoint(point); tx.releaseSavepoint(point);
        });
    }
    static Fixture seed(JdbcTemplate jdbc) {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), source = UUID.randomUUID();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'query-metadata-fixture')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)", source, project, "a".repeat(40), HASH);
        return new Fixture(owner, project, source);
    }
    static Task task(JdbcTemplate jdbc, Fixture fixture, String kind) {
        UUID id = UUID.randomUUID(); String key = id.toString().replace("-", "").repeat(2);
        if (kind.equals("PREPARATION")) {
            UUID edit = UUID.randomUUID();
            jdbc.update("INSERT INTO source_edit_leases(id,project_id,owner_id,task_id,base_version_id,base_revision) VALUES (?,?,?,?,?,1)", edit, fixture.project, fixture.owner, id, fixture.source);
            jdbc.update("""
                    INSERT INTO dependency_preparations(id,project_id,owner_id,edit_lease_id,base_version_id,base_revision,
                        tree_sha256,manifest_sha256,policy_sha256,idempotency_key_sha256,request_sha256,
                        queue_slot,queue_capacity,worker_capacity,task_timeout_seconds,queue_timeout_seconds)
                    VALUES (?,?,?,?,?,1,?,?,?,?,?,1,8,1,60,60)
                    """, id, fixture.project, fixture.owner, edit, fixture.source, HASH, HASH, HASH, key, HASH);
            return new Task(id, "dependency_preparations", "dependency_preparation_events", "task_id", "cancel_requested");
        }
        if (kind.equals("RUN")) {
            var build = task(jdbc, fixture, "BUILD");
            jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", build.id);
            for (String stage : List.of("INSTALLING", "CHECKING", "PACKAGING")) jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", stage, build.id);
            jdbc.update("UPDATE execution_builds SET status='SUCCEEDED',jar_bytes=1,jar_sha256=?,manifest_sha256=?,worker_slot=null,finished_at=clock_timestamp() WHERE id=?", HASH, HASH, build.id);
            var registry = new ExecutionBindingRepository(jdbc);
            var rows = jdbc.queryForList("SELECT id FROM execution_database_bindings WHERE project_id=?", UUID.class, fixture.project);
            ExecutionBindingRepository.Target target;
            if (rows.isEmpty()) {
                int port = jdbc.queryForObject("SELECT n FROM generate_series(22000,65000) n WHERE NOT EXISTS(SELECT FROM execution_database_bindings WHERE application_port=n) LIMIT 1", Integer.class);
                String name = "query_fixture_" + fixture.project.toString().replace("-", "");
                long oid = (fixture.project.getLeastSignificantBits() & 0x3fffffffL) + 100000;
                target = new ExecutionBindingRepository.Target(fixture.project, UUID.randomUUID(), 5432, name, name + "_r", port, oid, oid + 1);
                registry.reserve(target, 4);
            } else target = registry.get(fixture.project).target();
            var run = new ExecutionRunRepository(jdbc).admit(fixture.owner, fixture.project, ExecutionRun.request(build.id, target.id(), null, key));
            return new Task(run.id(), "execution_runs", "execution_run_events", "task_id", "stop_requested");
        }
        jdbc.update("INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,request_sha256,queue_slot) VALUES (?,?,?,?,?,?,?,1)",
                id, fixture.project, fixture.owner, fixture.source, HASH, key, HASH);
        return new Task(id, "execution_builds", "execution_build_events", "build_id", "cancel_requested");
    }
    static void finish(JdbcTemplate jdbc, Task task) {
        if (task.table.equals("dependency_preparations")) jdbc.update("UPDATE source_edit_leases SET status='RELEASED',released_at=now() WHERE task_id=?", task.id);
        if (task.table.equals("execution_runs")) jdbc.update("UPDATE execution_runs SET status='CANCELLED',stop_requested=true,error_code='RUN_CANCELLED',finished_at=clock_timestamp() WHERE id=?", task.id);
        else jdbc.update("UPDATE " + task.table + " SET status='CANCELLED',cancel_requested=true,error_code='EXECUTION_CANCELLED',queue_slot=null,finished_at=clock_timestamp() WHERE id=?", task.id);
    }
}
