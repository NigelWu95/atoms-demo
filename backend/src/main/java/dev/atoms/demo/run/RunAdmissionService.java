package dev.atoms.demo.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import dev.atoms.demo.model.ModelGateway;
import dev.atoms.demo.project.ProjectService;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunAdmissionService {
    public record Input(String prompt, String intent, UUID baseVersionId, long expectedProjectRevision) {}
    public record Availability(boolean configured, long remainingRequests, String model) {}
    private final RunRepository repo;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ProjectService projects;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final ModelGateway model;
    private final long ceiling;
    public RunAdmissionService(RunRepository repo, ProjectService projects, IdempotencyService idempotency,
                               ObjectMapper json, ModelGateway model, Environment env, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repo = repo; this.projects = projects; this.idempotency = idempotency; this.json = json; this.model = model;
        this.jdbc = jdbc;
        ceiling = Math.max(0, env.getProperty("GENERATION_MAX_MODEL_CALLS", Long.class, 0L));
    }
    public Availability availability() {
        long used = jdbc.queryForObject("SELECT requests_reserved FROM generation_budget WHERE id = 1", Long.class);
        return new Availability(model.configured(), Math.max(0, ceiling - used), "deepseek-flash");
    }
    @Transactional(timeout = 5)
    public IdempotencyService.Result submit(UUID owner, String projectId, JsonNode body, String key) {
        Input input = parse(body);
        long used = repo.lockBudget();
        var project = projects.lock(owner, projectId);
        return idempotency.execute(owner, "POST", "/api/projects/" + project.id() + "/runs", key, input, () -> {
            if (!model.configured()) throw new ApiException(503, "MODEL_NOT_CONFIGURED", "尚未配置 DeepSeek API Key，请联系本地开发者。");
            if (ceiling - used < 2) throw new ApiException(429, "GENERATION_LIMIT_REACHED", "剩余调用次数不足；首次生成至少需要两次模型请求，请调整服务端额度。");
            if (project.activeRunId() != null) throw new ApiException(409, "RUN_ALREADY_ACTIVE", "该项目已有生成任务，请等待或取消。");
            if (project.projectRevision() != input.expectedProjectRevision() || !Objects.equals(project.currentVersionId(), input.baseVersionId())
                    || ("CREATE".equals(input.intent()) != (project.currentVersionId() == null)))
                throw new ApiException(409, "BASE_VERSION_CONFLICT", "项目代码已更新，请刷新后再提交。");
            int active = jdbc.queryForObject("SELECT count(*) FROM runs WHERE status IN ('QUEUED', 'RUNNING')", Integer.class);
            if (active >= 8) throw new ApiException(429, "GENERATION_LIMIT_REACHED", "生成队列已满，请稍后重试。");
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO runs(id, project_id, owner_id, prompt, intent, base_version_id, expected_project_revision, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED')",
                    id, project.id(), owner, input.prompt(), input.intent(), input.baseVersionId(), input.expectedProjectRevision());
            jdbc.update("UPDATE projects SET active_run_id = ? WHERE id = ?", id, project.id());
            Run run = repo.get(owner, id.toString());
            repo.message(run, "USER", input.prompt()); repo.event(run, "run.queued", Map.of("status", "QUEUED"));
            return new IdempotencyService.Result(202, json.valueToTree(Map.of("runId", id, "status", "QUEUED")));
        });
    }
    @Transactional(timeout = 5)
    public Run cancel(UUID owner, String id) {
        Run run = repo.lock(owner, id);
        if ("CANCELLED".equals(run.status())) return run;
        if (!run.active()) throw new ApiException(409, "RUN_TERMINAL", "任务已经结束，不能取消。");
        repo.terminal(run, "CANCELLED", null, "RUN_CANCELLED", "生成已取消；已发出的模型请求仍可能计费。");
        return repo.get(owner, id);
    }
    private Input parse(JsonNode body) {
        if (!body.isObject() || body.size() != 4 || !body.path("prompt").isTextual() || !body.path("intent").isTextual()
                || !body.has("baseVersionId") || !(body.path("baseVersionId").isNull() || body.path("baseVersionId").isTextual())) throw invalid();
        String prompt = body.path("prompt").asText().strip(); String intent = body.path("intent").asText();
        var revision = body.path("expectedProjectRevision");
        if (prompt.isEmpty() || prompt.length() > 8000 || prompt.indexOf('\0') >= 0 || !("CREATE".equals(intent) || "MODIFY".equals(intent))
                || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 0 || revision.longValue() > 9007199254740991L) throw invalid();
        UUID base = null;
        if (!body.path("baseVersionId").isNull()) {
            try { base = UUID.fromString(body.path("baseVersionId").asText()); if (!base.toString().equals(body.path("baseVersionId").asText())) throw invalid(); }
            catch (IllegalArgumentException error) { throw invalid(); }
        }
        return new Input(prompt, intent, base, revision.longValue());
    }
    private ApiException invalid() { return new ApiException(422, "INVALID_INPUT", "只接受 1–8000 字符需求、CREATE/MODIFY、代码基线与非负安全修订号。"); }
}
