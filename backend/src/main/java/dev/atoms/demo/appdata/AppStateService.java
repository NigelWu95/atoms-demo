package dev.atoms.demo.appdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import dev.atoms.demo.project.Project;
import dev.atoms.demo.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppStateService {
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    public AppStateService(JdbcTemplate jdbc, ProjectService projects, ArtifactService artifacts, IdempotencyService idempotency, ObjectMapper json) {
        this.jdbc = jdbc; this.projects = projects; this.artifacts = artifacts; this.idempotency = idempotency; this.json = json;
    }
    @Transactional(timeout = 5)
    public StateDocument get(UUID actor, String id, String versionId) {
        var project = projects.lock(actor, id);
        requireCurrent(project, versionId);
        var rows = jdbc.query("SELECT value_json, revision, state_schema_version FROM app_states WHERE project_id = ? AND environment = 'PREVIEW' AND actor_id = ?",
                (rs, row) -> new StateDocument(decode(rs.getString("value_json")), rs.getLong("revision"), rs.getInt("state_schema_version")), project.id(), actor);
        return rows.isEmpty() ? new StateDocument(artifacts.get(actor, id, versionId).artifact().manifest().initialState(), 0, 1) : rows.get(0);
    }
    @Transactional(timeout = 5)
    public IdempotencyService.Result save(UUID actor, String id, JsonNode input, String key) {
        validate(input);
        var project = projects.lock(actor, id);
        // Authorize even a replay. Only NEW intents check the mutable code pointer/revision.
        return idempotency.execute(actor, "PUT", "/api/projects/" + project.id() + "/preview-state", key, input, () -> {
            requireCurrent(project, input.path("loadedVersionId").asText());
            long expected = input.path("expectedRevision").longValue();
            String value = input.path("value").toString();
            int changed = expected == 0 ? jdbc.update("""
                    INSERT INTO app_states(project_id, environment, actor_id, value_json, revision, state_schema_version)
                    VALUES (?, 'PREVIEW', ?, ?, 1, 1) ON CONFLICT (project_id, environment, actor_id) DO NOTHING
                    """, project.id(), actor, value) : jdbc.update("""
                    UPDATE app_states SET value_json = ?, revision = revision + 1, updated_at = now()
                    WHERE project_id = ? AND environment = 'PREVIEW' AND actor_id = ? AND revision = ?
                    """, value, project.id(), actor, expected);
            if (changed != 1) throw new ApiException(409, "STATE_CONFLICT", "数据已在另一处更新。请保留输入并重新读取，不能直接覆盖。");
            return new IdempotencyService.Result(200, json.valueToTree(new StateDocument(input.path("value"), expected + 1, 1)));
        });
    }
    private void requireCurrent(Project project, String versionId) {
        if (project.currentVersionId() == null || !project.currentVersionId().toString().equals(versionId)) {
            throw new ApiException(409, "STALE_APP_VERSION", "当前代码版本已经变化，请重新打开项目。");
        }
    }
    private void validate(JsonNode input) {
        if (!input.isObject() || input.size() != 4 || !input.path("loadedVersionId").isTextual()
                || !input.path("expectedRevision").isIntegralNumber() || !input.path("expectedRevision").canConvertToLong()
                || input.path("expectedRevision").longValue() < 0 || input.path("expectedRevision").longValue() >= 9007199254740991L
                || !input.path("stateSchemaVersion").isIntegralNumber() || !input.path("stateSchemaVersion").canConvertToInt()
                || input.path("stateSchemaVersion").intValue() != 1 || !input.path("value").isObject()
                || input.path("value").toString().getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw new ApiException(422, "INVALID_INPUT", "状态必须是 256 KiB 以内的 JSON 对象；仅支持 schema 1 和有效的修订号。");
        }
    }
    private JsonNode decode(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Stored state cannot be decoded"); }
    }
}
