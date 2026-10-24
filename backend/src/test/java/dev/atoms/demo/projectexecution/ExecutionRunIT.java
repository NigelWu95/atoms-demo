package dev.atoms.demo.projectexecution;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Rolled-back metadata fixtures only: no builds, processes, stop receipts or business migrations are performed. */
@ActiveProfiles("test") @Transactional
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionRunIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), source = UUID.randomUUID(), build = UUID.randomUUID(), binding = UUID.randomUUID();
    static final String TREE = "a".repeat(64), JAR = "b".repeat(64), MANIFEST = "c".repeat(64);
    ExecutionRunRepository repository;

    @BeforeEach void metadata() {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        repository = new ExecutionRunRepository(jdbc);
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'run-metadata-fixture')", project, owner);
        jdbc.update("INSERT INTO project_sources(project_id) VALUES (?)", project);
        jdbc.update("INSERT INTO source_versions(id,project_id,version_no,commit_id,tree_sha256,file_count,tree_bytes) VALUES (?,?,1,?,?,1,1)", source, project, "a".repeat(40), TREE);
        String name = "run_fixture_" + project.toString().replace("-", "");
        new ExecutionBindingRepository(jdbc).reserve(new ExecutionBindingRepository.Target(project, binding, 5432, name,
                name + "_r", 29001, 900001, 900002), 4);
        successfulBuild(build);
    }

    private void successfulBuild(UUID id) {
        jdbc.update("""
                INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,request_sha256,queue_slot)
                VALUES (?,?,?,?,?,?,?,1)
                """, id, project, owner, source, TREE, id.toString().replace("-", "").repeat(2), TREE);
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", id);
        for (String stage : List.of("INSTALLING", "CHECKING", "PACKAGING")) jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", stage, id);
        jdbc.update("UPDATE execution_builds SET status='SUCCEEDED',jar_bytes=1,jar_sha256=?,manifest_sha256=?,worker_slot=null,finished_at=clock_timestamp() WHERE id=?", JAR, MANIFEST, id);
    }

    @Test void runPinsSuccessfulBuildSourceAndDatabaseWithoutRecordingAProcess() {
        var request = request("one", null); var run = repository.admit(owner, project, request);
        assertThat(run.status()).isEqualTo("STARTING"); assertThat(run.executionPending()).isFalse();
        assertThat(run.executionId()).isNull(); assertThat(run.readyAt()).isNull(); assertThat(run.finishedAt()).isNull();
        assertThat(run.buildId()).isEqualTo(build); assertThat(run.sourceVersionId()).isEqualTo(source);
        assertThat(run.bindingId()).isEqualTo(binding); assertThat(run.jarSha256()).isEqualTo(JAR);
        assertThat(run.treeSha256()).isEqualTo(TREE); assertThat(run.manifestSha256()).isEqualTo(MANIFEST);
        assertThat(repository.admit(owner, project, request)).isEqualTo(run);
        assertThat(repository.get(project, run.id())).isEqualTo(run);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE project_id=?", Integer.class, project)).isEqualTo(1);
    }

    @Test void projectCannotStartAnotherRunUntilTheOriginalOneHasFinished() {
        var first = repository.admit(owner, project, request("one", null));
        assertCode(() -> repository.admit(owner, project, request("two", null)), "RUN_PROJECT_BUSY");
        jdbc.update("UPDATE execution_runs SET status='CANCELLED',stop_requested=true,error_code='RUN_CANCELLED',finished_at=clock_timestamp() WHERE id=?", first.id());
        var second = repository.admit(owner, project, request("two", null));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(repository.admit(owner, project, request("one", null)).status()).isEqualTo("CANCELLED");
        rejected(() -> jdbc.update("UPDATE execution_runs SET error_code='RUN_OTHER_FAILURE' WHERE id=?", first.id()));
        assertThat(new ExecutionBindingRepository(jdbc).get(project).target().id()).isEqualTo(binding);
    }

    @Test void keyConflictAndWrongBuildBindingOrOwnerDoNotCreateRuns() {
        var first = repository.admit(owner, project, request("one", null));
        assertCode(() -> repository.admit(owner, project, ExecutionRun.request(UUID.randomUUID(), binding, null, "one")), "RUN_IDEMPOTENCY_CONFLICT");
        assertCode(() -> repository.admit(UUID.randomUUID(), project, request("three", null)), "PROJECT_NOT_FOUND");
        jdbc.update("UPDATE execution_runs SET status='CANCELLED',finished_at=clock_timestamp() WHERE id=?", first.id());
        assertCode(() -> repository.admit(owner, project, ExecutionRun.request(UUID.randomUUID(), binding, null, "missing-build")), "RUN_BUILD_NOT_READY");
        assertCode(() -> repository.admit(owner, project, ExecutionRun.request(build, UUID.randomUUID(), null, "missing-binding")), "RUN_DB_BINDING_CONFLICT");
        jdbc.update("UPDATE projects SET archived=true WHERE id=?", project);
        assertCode(() -> repository.admit(owner, project, request("archived", null)), "RUN_PROJECT_ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE project_id=?", Integer.class, project)).isEqualTo(1);
    }

    @Test void databaseRejectsReadyWithoutOwnedExecutionAndChangesToPinnedIdentity() {
        var run = repository.admit(owner, project, request("immutable", null));
        rejected(() -> jdbc.update("UPDATE execution_runs SET status='READY',ready_at=clock_timestamp() WHERE id=?", run.id()));
        rejected(() -> jdbc.update("UPDATE execution_runs SET jar_sha256=? WHERE id=?", TREE, run.id()));
        rejected(() -> jdbc.update("UPDATE execution_runs SET binding_identity_sha256=? WHERE id=?", TREE, run.id()));
        rejected(() -> jdbc.update("DELETE FROM execution_runs WHERE id=?", run.id()));
        assertThat(repository.get(project, run.id())).isEqualTo(run);
        jdbc.update("UPDATE execution_runs SET status='NEEDS_RECONCILIATION',error_code='RUN_RECOVERY_UNCERTAIN' WHERE id=?", run.id());
        assertCode(() -> repository.admit(owner, project, request("blocked", null)), "RUN_PROJECT_BUSY");
    }

    @Test void reviewPinsArtifactHistoryAndDatabaseAndCannotBeRewrittenOrReusedForAnotherBuild() {
        UUID review = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO execution_migration_reviews(id,project_id,binding_id,build_id,approved_by,jar_sha256,history_sha256,target_sha256)
                VALUES (?,?,?,?,?,?,?,?)
                """, review, project, binding, build, owner, JAR, TREE, MANIFEST);
        var run = repository.admit(owner, project, request("reviewed", review));
        assertThat(run.migrationReviewId()).isEqualTo(review);
        rejected(() -> jdbc.update("UPDATE execution_migration_reviews SET history_sha256=? WHERE id=?", JAR, review));
        rejected(() -> jdbc.update("DELETE FROM execution_migration_reviews WHERE id=?", review));
        rejected(() -> jdbc.update("""
                INSERT INTO execution_migration_reviews(id,project_id,binding_id,build_id,approved_by,jar_sha256,history_sha256,target_sha256)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), project, binding, build, owner, TREE, TREE, MANIFEST));
        jdbc.update("UPDATE execution_runs SET status='CANCELLED',finished_at=clock_timestamp() WHERE id=?", run.id());
        assertCode(() -> repository.admit(owner, project, request("wrong-review", UUID.randomUUID())), "RUN_MIGRATION_REVIEW_REQUIRED");
        UUID anotherBuild = UUID.randomUUID(); successfulBuild(anotherBuild);
        assertCode(() -> repository.admit(owner, project, ExecutionRun.request(anotherBuild, binding, review, "different-build")), "RUN_MIGRATION_REVIEW_REQUIRED");
    }

    @Test void requestValidationRejectsEmptyKeysControlCharactersAndMissingIds() {
        for (String key : List.of("", " ", "x".repeat(129), "a\nb", "\ud800"))
            assertCode(() -> ExecutionRun.request(build, binding, null, key), "RUN_INVALID_INPUT");
        assertCode(() -> ExecutionRun.request(null, binding, null, "one"), "RUN_INVALID_INPUT");
        assertCode(() -> ExecutionRun.request(build, null, null, "one"), "RUN_INVALID_INPUT");
    }

    private ExecutionRun.Request request(String key, UUID review) { return ExecutionRun.request(build, binding, review, key); }
    private void assertCode(Runnable work, String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(dev.atoms.demo.common.ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    private void rejected(Runnable work) {
        var nested = new TransactionTemplate(transactions); nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.executeWithoutResult(tx -> work.run())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
