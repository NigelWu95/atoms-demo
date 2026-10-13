package dev.atoms.demo.run;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.model.ModelException;
import dev.atoms.demo.model.ModelGateway;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions only. Lock order: budget (when needed), project, run. */
@Service
public class RunExecutionService {
    private final RunRepository repo;
    private final JdbcTemplate jdbc;
    private final ArtifactService artifacts;
    private final long ceiling;
    private final int timeoutSeconds;
    public RunExecutionService(RunRepository repo, JdbcTemplate jdbc, ArtifactService artifacts, Environment env) {
        this.repo = repo; this.jdbc = jdbc; this.artifacts = artifacts;
        ceiling = Math.max(0, env.getProperty("GENERATION_MAX_MODEL_CALLS", Long.class, 0L));
        timeoutSeconds = env.getProperty("GENERATION_TIMEOUT_SECONDS", Integer.class, 900);
        if (timeoutSeconds < 1 || timeoutSeconds > 1800) throw new IllegalArgumentException("Invalid generation timeout");
    }
    public Run next() {
        var queued = jdbc.query("SELECT * FROM runs WHERE status = 'QUEUED' ORDER BY ordinal LIMIT 1", RunRepository.ROW);
        return queued.isEmpty() ? null : queued.get(0);
    }
    @Transactional(timeout = 5)
    public Run start(UUID owner, String id) {
        repo.lockBudget();
        if (jdbc.queryForObject("SELECT count(*) FROM runs WHERE status = 'RUNNING'", Integer.class) > 0) return null;
        Run run = repo.lock(owner, id);
        if (!"QUEUED".equals(run.status())) return null;
        jdbc.update("UPDATE runs SET status = 'RUNNING', started_at = now(), updated_at = now() WHERE id = ?", run.id());
        return repo.get(owner, id);
    }
    @Transactional(timeout = 5)
    public UUID reserve(Run identity, String stage) {
        long used = repo.lockBudget(); Run run = repo.lock(identity.ownerId(), identity.id().toString()); requireRunning(run);
        if (used >= ceiling) throw new ModelException("GENERATION_LIMIT_REACHED", "模型请求次数额度已用完，未发出新的请求。");
        UUID call = UUID.randomUUID();
        jdbc.update("UPDATE generation_budget SET requests_reserved = requests_reserved + 1 WHERE id = 1");
        jdbc.update("INSERT INTO model_calls(id, run_id, stage) VALUES (?, ?, ?)", call, run.id(), stage);
        jdbc.update("UPDATE runs SET stage = ? WHERE id = ?", stage, run.id());
        repo.event(run, "stage.started", Map.of("stage", stage));
        return call;
    }
    public void recordUsage(UUID call, ModelGateway.Usage usage) {
        jdbc.update("UPDATE model_calls SET status = 'SUCCEEDED', prompt_tokens = ?, completion_tokens = ?, total_tokens = ? WHERE id = ?",
                usage == null ? null : usage.promptTokens(), usage == null ? null : usage.completionTokens(), usage == null ? null : usage.totalTokens(), call);
    }
    public void recordFailedUsage(UUID call, ModelGateway.Usage usage) {
        jdbc.update("UPDATE model_calls SET status = 'FAILED', prompt_tokens = ?, completion_tokens = ?, total_tokens = ? WHERE id = ?",
                usage == null ? null : usage.promptTokens(), usage == null ? null : usage.completionTokens(), usage == null ? null : usage.totalTokens(), call);
    }
    @Transactional(timeout = 5)
    public void plan(Run identity, JsonNode plan) {
        Run run = repo.lock(identity.ownerId(), identity.id().toString()); requireRunning(run);
        String features = java.util.stream.StreamSupport.stream(plan.path("features").spliterator(), false).map(JsonNode::asText).collect(java.util.stream.Collectors.joining("、"));
        repo.message(run, "PLANNER", plan.path("goal").asText() + "\n功能：" + features);
        repo.event(run, "plan.ready", Map.of("plan", plan));
    }
    @Transactional(timeout = 5)
    public void complete(Run identity, JsonNode source) {
        Run run = repo.lock(identity.ownerId(), identity.id().toString()); requireRunning(run);
        var version = artifacts.save(run.ownerId(), run.projectId().toString(), run.expectedProjectRevision(), run.baseVersionId(), source);
        repo.event(run, "artifact.created", Map.of("versionId", version.id(), "versionNo", version.versionNo()));
        repo.terminal(run, "SUCCEEDED", version.id(), null, null);
    }
    @Transactional(timeout = 5)
    public void fail(Run identity, String code, String message) {
        Run run = repo.lock(identity.ownerId(), identity.id().toString());
        if (run.active()) repo.terminal(run, "RUN_INTERRUPTED".equals(code) ? "INTERRUPTED" : "FAILED", null, code, message);
    }
    public void check(Run identity) { requireRunning(repo.get(identity.ownerId(), identity.id().toString())); }
    private void requireRunning(Run run) {
        if (!"RUNNING".equals(run.status())) throw new ModelException("RUN_CANCELLED", "任务已结束，晚到结果不会保存。");
        if (run.startedAt().plusSeconds(timeoutSeconds).isBefore(Instant.now())) throw new ModelException("RUN_TIMEOUT", "整轮生成超过时限，未自动重试。");
    }
    public java.util.List<Run> interrupted() { return jdbc.query("SELECT * FROM runs WHERE status = 'RUNNING'", RunRepository.ROW); }
    public java.util.List<Run> expired() {
        return jdbc.query("SELECT * FROM runs WHERE status = 'RUNNING' AND started_at < ?", RunRepository.ROW,
                java.sql.Timestamp.from(Instant.now().minusSeconds(timeoutSeconds)));
    }
}
