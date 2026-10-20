package dev.atoms.demo.projectexecution;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false",
        "atoms.generation.worker-enabled=false"})
class ExecutionBuildSchemaIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void additiveSchemaBindsImmutableSourceAndPreservesAudit() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), version = UUID.randomUUID(), build = UUID.randomUUID();
            jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
            jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'schema-test')", project, owner);
            jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
            jdbc.update("""
                    INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes)
                    VALUES (?,?,1,?, ?,1,1)
                    """, version, project, "a".repeat(40), "b".repeat(64));
            jdbc.update("""
                    INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,
                        idempotency_key_sha256,request_sha256,queue_slot)
                    VALUES (?,?,?,?,?,?,?,1)
                    """, build, project, owner, version, "b".repeat(64), "c".repeat(64), "d".repeat(64));
            assertThat(jdbc.queryForObject("SELECT status FROM execution_builds WHERE id=?", String.class, build)).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, build)).isEqualTo(1);
            for (String sql : new String[] {
                    "UPDATE execution_builds SET tree_sha256=repeat('e',64) WHERE id=?",
                    "UPDATE execution_builds SET status='SUCCEEDED',queue_slot=null,finished_at=now() WHERE id=?",
                    "UPDATE execution_builds SET queue_slot=9 WHERE id=?",
                    "UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=2 WHERE id=?",
                    "DELETE FROM execution_build_events WHERE build_id=?",
                    "UPDATE execution_build_events SET payload='{}'::jsonb WHERE build_id=?",
                    "DELETE FROM execution_builds WHERE id=?"}) {
                Object savepoint = status.createSavepoint();
                assertThatThrownBy(() -> jdbc.update(sql, build)).isInstanceOf(DataAccessException.class);
                status.rollbackToSavepoint(savepoint);
                status.releaseSavepoint(savepoint);
            }
            for (String exhausted : new String[] {"last_event_seq=1000", "event_bytes=65535"}) {
                Object budget = status.createSavepoint();
                jdbc.update("UPDATE execution_builds SET " + exhausted + " WHERE id=?", build);
                Object attempt = status.createSavepoint();
                assertThatThrownBy(() -> jdbc.update("UPDATE execution_builds SET cancel_requested=true WHERE id=?", build))
                        .isInstanceOf(DataAccessException.class);
                status.rollbackToSavepoint(attempt);
                assertThat(jdbc.queryForObject("SELECT cancel_requested FROM execution_builds WHERE id=?", Boolean.class, build)).isFalse();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, build)).isEqualTo(1);
                status.rollbackToSavepoint(budget);
                status.releaseSavepoint(budget);
            }
            Object active = status.createSavepoint();
            jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", build);
            jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION',execution_pending=true WHERE id=?", build);
            Object stop = status.createSavepoint();
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE execution_builds SET status='FAILED',execution_pending=false,
                        worker_slot=null,finished_at=now() WHERE id=?
                    """, build)).isInstanceOf(DataAccessException.class);
            status.rollbackToSavepoint(stop);
            assertThat(jdbc.queryForObject("SELECT worker_slot FROM execution_builds WHERE id=?", Integer.class, build)).isEqualTo(1);
            status.rollbackToSavepoint(active);
            status.releaseSavepoint(active);
            jdbc.update("""
                    UPDATE execution_builds SET status='CANCELLED',cancel_requested=true,
                        queue_slot=null,finished_at=now() WHERE id=?
                    """, build);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_build_events WHERE build_id=?", Integer.class, build)).isEqualTo(2);
            status.setRollbackOnly();
        });
    }
}
