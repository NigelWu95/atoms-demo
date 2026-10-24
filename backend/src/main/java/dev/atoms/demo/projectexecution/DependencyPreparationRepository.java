package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class DependencyPreparationRepository {
    record Task(UUID id, UUID projectId, UUID ownerId, UUID editLeaseId, UUID baseVersionId, long baseRevision,
                String treeSha256, String manifestSha256, String baseLockSha256, String policySha256,
                String requestSha256, String status, Instant createdAt, Instant finishedAt) {}
    record Request(UUID version, long revision, String keyHash, String bodyHash) {
        static Request from(DependencyPreparationService.Input input, String key) {
            if (input == null || input.expectedSourceRevision() < 1 || input.expectedSourceRevision() > 9007199254740991L)
                throw new ApiException(422, "EXECUTION_INVALID_INPUT", "源码基线或修订号无效。");
            var validated = ExecutionBuild.request(input.baseSourceVersionId(), key);
            try {
                byte[] bytes = ("dependency-preparation-v1\n" + validated.version() + "\n" + input.expectedSourceRevision()).getBytes(StandardCharsets.UTF_8);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                return new Request(validated.version(), input.expectedSourceRevision(), validated.keyHash(), hash);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    private static final RowMapper<Task> ROW = (rs, row) -> new Task(rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getObject("edit_lease_id", UUID.class),
            rs.getObject("base_version_id", UUID.class), rs.getLong("base_revision"), rs.getString("tree_sha256"),
            rs.getString("manifest_sha256"), rs.getString("base_lock_sha256"), rs.getString("policy_sha256"),
            rs.getString("request_sha256"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    private final JdbcTemplate jdbc;
    private final ExecutionBuildRepository capacity;
    DependencyPreparationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; capacity = new ExecutionBuildRepository(jdbc); }

    Task findKey(UUID project, String keyHash) {
        return jdbc.query("SELECT * FROM dependency_preparations WHERE project_id=? AND idempotency_key_sha256=?", ROW, project, keyHash)
                .stream().findFirst().orElse(null);
    }
    Task get(UUID project, UUID task) {
        return jdbc.query("SELECT * FROM dependency_preparations WHERE project_id=? AND id=?", ROW, project, task)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "EXECUTION_PREPARATION_NOT_FOUND", "依赖准备任务不存在或无权访问。"));
    }
    void requireIdle(UUID project) { capacity.requireIdle(project); }

    Task insert(UUID id, UUID owner, UUID project, UUID edit, Request request, String tree,
                ExecutionNpmPolicy.Preparation approved, ExecutionSettings.Configuration config) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Preparation admission requires a transaction");
        requireIdle(project);
        capacity.lockCapacity();
        var slots = jdbc.queryForList("""
                SELECT slot FROM generate_series(1,?) slot
                WHERE (SELECT count(*) FROM execution_task_slots WHERE queue_slot IS NOT NULL) < ?
                    AND NOT EXISTS(SELECT 1 FROM execution_task_slots WHERE queue_slot=slot)
                ORDER BY slot LIMIT 1
                """, Integer.class, config.queueCapacity(), config.queueCapacity());
        if (slots.isEmpty()) throw new ApiException(429, "EXECUTION_QUEUE_FULL", "执行队列已满，请稍后重试。");
        jdbc.update("""
                INSERT INTO dependency_preparations(id,project_id,owner_id,edit_lease_id,base_version_id,base_revision,
                    tree_sha256,manifest_sha256,base_lock_sha256,policy_sha256,idempotency_key_sha256,request_sha256,
                    queue_slot,queue_capacity,worker_capacity,task_timeout_seconds,queue_timeout_seconds)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, project, owner, edit, request.version(), request.revision(), tree, approved.manifestSha256(),
                approved.baseLockSha256(), approved.policySha256(), request.keyHash(), request.bodyHash(), slots.get(0),
                config.queueCapacity(), config.maxConcurrentBuilds(), config.taskTimeout().toSeconds(), config.queueTimeout().toSeconds());
        return get(project, id);
    }
}
