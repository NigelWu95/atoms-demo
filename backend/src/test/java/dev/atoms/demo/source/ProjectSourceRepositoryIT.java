package dev.atoms.demo.source;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.source.enabled=false", "atoms.email.enabled=false", "atoms.generation.worker-enabled=false"})
class ProjectSourceRepositoryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), other = UUID.randomUUID();

    @BeforeEach void requireDedicatedDatabaseAndCreateTestProjects() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("atoms_demo_test");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("atoms_test");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        for (UUID id : java.util.List.of(project, other)) jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", id, owner, "source-test");
    }

    @Test void storesOnlyMetadataAndKeepsLegacyPointersUntouched() {
        UUID version = UUID.randomUUID();
        source(project); insertVersion(version, project, 1, null);
        var repository = new SourceRepository(jdbc);
        assertThat(repository.version(project, version).commitId()).isEqualTo("a".repeat(40));
        assertThat(repository.version(project, version).treeSha256()).isEqualTo("b".repeat(64));
        assertThat(repository.version(other, version)).isNull();
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM projects WHERE id=?", UUID.class, project)).isNull();
        assertThat(jdbc.queryForObject("SELECT project_revision FROM projects WHERE id=?", Long.class, project)).isZero();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='atoms' AND table_name IN ('project_sources','source_versions','source_operations')", String.class))
                .doesNotContain("source_json", "source_code", "content", "file_bytes");
    }

    @Test void rejectsCrossProjectPointersParentsAndVersionMutation() {
        source(project); source(other);
        UUID version = UUID.randomUUID(); insertVersion(version, project, 1, null);
        refused(() -> jdbc.update("UPDATE project_sources SET current_source_version_id=?,source_revision=1 WHERE project_id=?", version, other));
        refused(() -> insertVersion(UUID.randomUUID(), other, 1, version));
        refused(() -> insertVersion(UUID.randomUUID(), project, 1, null));
        refused(() -> jdbc.update("UPDATE source_versions SET tree_sha256=? WHERE id=?", "c".repeat(64), version));
        refused(() -> jdbc.update("DELETE FROM source_versions WHERE id=?", version));
        assertThat(new SourceRepository(jdbc).version(project, version).treeSha256()).isEqualTo("b".repeat(64));
    }

    @Test void operationIntentIsImmutableAndIdempotencyKeyCannotBeReused() {
        source(project);
        UUID operation = UUID.randomUUID(), candidate = UUID.randomUUID();
        insertOperation(operation, candidate, "c".repeat(64));
        var repository = new SourceRepository(jdbc);
        assertThat(repository.operation(project, operation).candidateVersionId()).isEqualTo(candidate);
        assertThat(repository.findByKey(project, "c".repeat(64)).id()).isEqualTo(operation);
        refused(() -> insertOperation(UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64)));
        refused(() -> jdbc.update("UPDATE source_operations SET request_sha256=? WHERE id=?", "d".repeat(64), operation));
        refused(() -> jdbc.update("UPDATE source_operations SET candidate_version_id=? WHERE id=?", UUID.randomUUID(), operation));
        refused(() -> jdbc.update("UPDATE source_operations SET status='COMMITTED',finished_at=now() WHERE id=?", operation));
        jdbc.update("UPDATE source_operations SET status='CANCELLED',finished_at=now() WHERE id=?", operation);
        refused(() -> jdbc.update("UPDATE source_operations SET status='PREPARING',finished_at=NULL WHERE id=?", operation));
        assertThat(repository.operation(project, operation).status()).isEqualTo("CANCELLED");
    }

    private void source(UUID id) { jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", id); }
    private void insertVersion(UUID id, UUID projectId, long number, UUID parent) {
        jdbc.update("""
                INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,based_on_id,file_count,tree_bytes)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, projectId, number, "a".repeat(40), "b".repeat(64), parent, 3, 256);
    }
    private void insertOperation(UUID id, UUID candidate, String key) {
        jdbc.update("""
                INSERT INTO source_operations(id,project_id,owner_id,kind,idempotency_sha256,request_sha256,expected_revision,candidate_version_id)
                VALUES (?,?,?,'INITIALIZE',?,?,0,?)
                """, id, project, owner, key, "f".repeat(64), candidate);
    }
    private void refused(Runnable action) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> action.run()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
