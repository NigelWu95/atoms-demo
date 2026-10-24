package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Short metadata transactions only. Inspection DTOs are not launch or credential capabilities. */
final class ExecutionMigrationReviewRepository {
    record Identity(ExecutionBuild build, String jarSha256, String manifestSha256, ExecutionBindingRepository.Target binding) {}
    private final JdbcTemplate jdbc;
    ExecutionMigrationReviewRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Identity identity(UUID owner, UUID project, UUID build) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Migration review requires a transaction");
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        var projects = jdbc.queryForList("SELECT owner_id,archived FROM projects WHERE id=? FOR UPDATE", project);
        if (projects.isEmpty() || owner == null || !owner.equals(projects.get(0).get("owner_id"))) throw ExecutionRun.error(404,"PROJECT_NOT_FOUND");
        if (Boolean.TRUE.equals(projects.get(0).get("archived"))) throw ExecutionRun.error(409,"RUN_PROJECT_ARCHIVED");
        var builds = new ExecutionBuildRepository(jdbc);
        var current = builds.get(project, build);
        if (!current.ownerId().equals(owner) || !"SUCCEEDED".equals(current.status())) throw ExecutionRun.error(409,"RUN_BUILD_NOT_READY");
        var artifact = jdbc.queryForMap("SELECT jar_sha256,manifest_sha256 FROM execution_builds WHERE id=?",build);
        var binding = new ExecutionBindingRepository(jdbc).get(project);
        if (binding == null) throw ExecutionRun.error(409,"RUN_DB_BINDING_NOT_FOUND");
        return new Identity(current, (String) artifact.get("jar_sha256"), (String) artifact.get("manifest_sha256"), binding.target());
    }

    ExecutionMigrationPolicy.Decision validate(UUID owner, Identity expected, ExecutionMigrations.Target target, ExecutionMigrationHistory.Snapshot database) {
        var current = identity(owner,expected.build().projectId(),expected.build().id());
        if (!current.equals(expected) || !target.project().equals(current.build().projectId()) || !target.build().equals(current.build().id())
                || !target.source().equals(current.build().sourceVersionId()) || !target.treeSha256().equals(current.build().treeSha256())
                || !target.jarSha256().equals(current.jarSha256()) || !target.manifestSha256().equals(current.manifestSha256())
                || !hash(target.sha256()) || !hash(database.state().sha256())) throw ExecutionRun.error(409,"RUN_MIGRATION_INPUT_CHANGED");
        if (!current.binding().equals(database.binding())) throw ExecutionRun.error(409,"RUN_DB_BINDING_CONFLICT");
        var state = database.state();
        try { return ExecutionMigrationPolicy.evaluate(target.migrations(),state.exists(),state.objects(),state.applied()); }
        catch (IOException incompatible) { throw ExecutionRun.error(409,"RUN_MIGRATION_INCOMPATIBLE"); }
    }

    UUID approve(UUID owner, Identity expected, ExecutionMigrations.Target target, ExecutionMigrationHistory.Snapshot database,
                 String expectedHistory, String expectedTarget) {
        if (!hash(expectedHistory) || !hash(expectedTarget)) throw ExecutionRun.error(422,"RUN_INVALID_INPUT");
        var decision = validate(owner,expected,target,database);
        if (!expectedHistory.equals(database.state().sha256()) || !expectedTarget.equals(target.sha256())) throw ExecutionRun.error(409,"RUN_MIGRATION_REVIEW_STALE");
        if (decision != ExecutionMigrationPolicy.Decision.REVIEW_REQUIRED) throw ExecutionRun.error(409,"RUN_MIGRATION_REVIEW_NOT_REQUIRED");
        var previous = jdbc.queryForList("""
                SELECT id,approved_by FROM execution_migration_reviews WHERE project_id=? AND binding_id=? AND build_id=?
                  AND jar_sha256=? AND history_sha256=? AND target_sha256=?
                """,target.project(),database.binding().id(),target.build(),target.jarSha256(),expectedHistory,expectedTarget);
        if (!previous.isEmpty()) {
            if (!owner.equals(previous.get(0).get("approved_by"))) throw ExecutionRun.error(409,"RUN_MIGRATION_REVIEW_STALE");
            return (UUID) previous.get(0).get("id");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO execution_migration_reviews(id,project_id,binding_id,build_id,approved_by,jar_sha256,history_sha256,target_sha256)
                VALUES (?,?,?,?,?,?,?,?)
                """,id,target.project(),database.binding().id(),target.build(),owner,target.jarSha256(),expectedHistory,expectedTarget);
        return id;
    }

    void requireCompatible(UUID owner, Identity expected, ExecutionMigrations.Target target, ExecutionMigrationHistory.Snapshot database, UUID review) {
        var decision = validate(owner,expected,target,database);
        if (review == null && decision != ExecutionMigrationPolicy.Decision.REVIEW_REQUIRED) return;
        if (review == null || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT FROM execution_migration_reviews WHERE id=? AND project_id=? AND binding_id=? AND build_id=?
                  AND approved_by=? AND jar_sha256=? AND history_sha256=? AND target_sha256=?)
                """,Boolean.class,review,target.project(),database.binding().id(),target.build(),owner,target.jarSha256(),database.state().sha256(),target.sha256())))
            throw ExecutionRun.error(409,"RUN_MIGRATION_REVIEW_REQUIRED");
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
