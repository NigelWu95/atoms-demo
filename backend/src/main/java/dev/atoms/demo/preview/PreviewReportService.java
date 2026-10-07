package dev.atoms.demo.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import dev.atoms.demo.project.ProjectService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreviewReportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ArtifactService artifacts;
    private final ProjectService projects;
    private final IdempotencyService idempotency;
    public PreviewReportService(JdbcTemplate jdbc, ObjectMapper json, ArtifactService artifacts,
                                ProjectService projects, IdempotencyService idempotency) {
        this.jdbc = jdbc; this.json = json; this.artifacts = artifacts; this.projects = projects; this.idempotency = idempotency;
    }
    @Transactional(timeout = 5)
    public IdempotencyService.Result save(UUID owner, String projectId, String versionId, JsonNode body, String key) {
        var project = projects.lock(owner, projectId);
        var version = artifacts.get(owner, projectId, versionId);
        validate(body);
        return idempotency.execute(owner, "POST", "/api/projects/" + projectId + "/versions/" + versionId + "/preview-reports", key, body, () -> {
            if (!version.id().equals(project.currentVersionId()))
                throw new ApiException(409, "STALE_APP_VERSION", "请打开当前版本后重新检查，历史版本不能创建新报告。");
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO preview_reports(id, project_id, version_id, status, payload_json) VALUES (?, ?, ?, ?, ?)",
                    id, project.id(), version.id(), body.path("status").asText(), body.toString());
            return new IdempotencyService.Result(201, json.createObjectNode().put("reportId", id.toString()).put("status", body.path("status").asText()));
        });
    }
    public ObjectNode latest(UUID owner, String projectId, String versionId) {
        var version = artifacts.get(owner, projectId, versionId);
        var rows = jdbc.query("SELECT id, payload_json, created_at FROM preview_reports WHERE version_id = ? ORDER BY ordinal DESC LIMIT 1", (rs, row) -> {
            try {
                var report = (ObjectNode) json.readTree(rs.getString("payload_json"));
                return report.put("reportId", rs.getString("id")).put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Stored preview report cannot be decoded"); }
        }, version.id());
        if (!rows.isEmpty()) return rows.get(0);
        var unknown = json.createObjectNode().put("status", "UNKNOWN").putNull("reportId").putNull("createdAt").putNull("viewport");
        unknown.putArray("checks"); unknown.putArray("errors"); return unknown;
    }
    private void validate(JsonNode body) {
        if (!body.isObject() || body.size() != 4 || !body.path("status").isTextual()
                || !body.path("checks").isArray() || !body.path("errors").isArray() || !body.path("viewport").isObject()) throw invalid();
        String status = body.path("status").asText();
        if (!("PASSED".equals(status) || "FAILED".equals(status))) throw invalid();
        Set<String> checks = new HashSet<>();
        for (JsonNode check : body.path("checks")) {
            if (!check.isTextual() || !Set.of("SCRIPT_PARSE", "SDK_STARTUP", "RUNTIME_ERRORS").contains(check.asText()) || !checks.add(check.asText())) throw invalid();
        }
        var errors = body.path("errors");
        if (errors.size() > 5 || ("PASSED".equals(status) && (checks.size() != 3 || !errors.isEmpty()))
                || ("FAILED".equals(status) && errors.isEmpty())) throw invalid();
        for (JsonNode error : errors) if (!error.isTextual() || error.asText().isBlank() || error.asText().length() > 1000 || error.asText().indexOf('\0') >= 0) throw invalid();
        var viewport = body.path("viewport");
        if (viewport.size() != 2) throw invalid();
        for (String dimension : Set.of("width", "height")) {
            var value = viewport.path(dimension);
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 10000) throw invalid();
        }
    }
    private ApiException invalid() { return new ApiException(422, "INVALID_PREVIEW_REPORT", "检查报告格式无效：通过须完成全部检查，失败须附受限错误和有效视口。"); }
}
