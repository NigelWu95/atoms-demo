package dev.atoms.demo.publication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import dev.atoms.demo.preview.PreviewReportService;
import dev.atoms.demo.project.ProjectService;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationService {
    public record Publication(String slug, UUID versionId, Long versionNo, String status, long revision, String publicPath, String publicUrl) {}
    private static final long MAX_REVISION = 9007199254740991L;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectService projects;
    private final PreviewReportService reports;
    private final IdempotencyService idempotency;
    private final String origin;
    public PublicationService(JdbcTemplate jdbc, ObjectMapper json, ProjectService projects,
            PreviewReportService reports, IdempotencyService idempotency, Environment env) {
        this.jdbc = jdbc; this.json = json; this.projects = projects; this.reports = reports; this.idempotency = idempotency;
        origin = publicOrigin(env.getProperty("PUBLIC_APP_ORIGIN", ""),
                env.getProperty("ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN", Boolean.class, false));
    }
    public Publication get(UUID owner, String id) { return stored(projects.get(owner, id).id()); }

    @Transactional(timeout = 5)
    public IdempotencyService.Result publish(UUID owner, String id, JsonNode input, String key) {
        var project = projects.lock(owner, id);
        if (!input.isObject() || input.size() != 3 || !input.path("versionId").isTextual()) throw invalid();
        UUID version;
        try {
            version = UUID.fromString(input.path("versionId").asText());
            if (!version.toString().equals(input.path("versionId").asText())) throw invalid();
        } catch (IllegalArgumentException error) { throw invalid(); }
        long expectedProject = revision(input.path("expectedProjectRevision"));
        long expectedPublication = revision(input.path("expectedPublicationRevision"));
        return idempotency.execute(owner, "PUT", path(id), key, input, () -> {
            var previous = stored(project.id());
            if (project.activeRunId() != null) throw new ApiException(409, "RUN_ALREADY_ACTIVE", "生成仍在进行，请等待或取消后再发布。");
            if (project.projectRevision() != expectedProject || !version.equals(project.currentVersionId()))
                throw new ApiException(409, "BASE_VERSION_CONFLICT", "当前代码版本已经变化，请刷新后重新确认发布。");
            requireRevision(previous, expectedPublication);
            if (!"PASSED".equals(reports.latest(owner, id, version.toString()).path("status").asText()))
                throw new ApiException(409, "PREVIEW_CHECK_REQUIRED", "请在当前版本完成预览检查并保存通过报告后再发布。");
            if (previous.revision() == 0) {
                jdbc.update("INSERT INTO publications(project_id, slug, version_id, status, revision) VALUES (?, ?, ?, 'PUBLISHED', 1)",
                        project.id(), UUID.randomUUID().toString().replace("-", ""), version);
            } else {
                jdbc.update("UPDATE publications SET version_id = ?, status = 'PUBLISHED', revision = revision + 1, updated_at = now() WHERE project_id = ?", version, project.id());
            }
            return new IdempotencyService.Result(200, json.valueToTree(stored(project.id())));
        });
    }
    @Transactional(timeout = 5)
    public void unpublish(UUID owner, String id, long expectedRevision, String key) {
        var project = projects.lock(owner, id);
        if (expectedRevision < 0 || expectedRevision >= MAX_REVISION) throw invalid();
        idempotency.execute(owner, "DELETE", path(id), key, json.createObjectNode().put("expectedPublicationRevision", expectedRevision), () -> {
            var previous = stored(project.id());
            requireRevision(previous, expectedRevision);
            if ("PUBLISHED".equals(previous.status()))
                jdbc.update("UPDATE publications SET status = 'UNPUBLISHED', revision = revision + 1, updated_at = now() WHERE project_id = ?", project.id());
            return new IdempotencyService.Result(204, json.nullNode());
        });
    }
    private Publication stored(UUID projectId) {
        var rows = jdbc.query("SELECT p.slug, p.version_id, p.status, p.revision, v.version_no FROM publications p JOIN versions v ON v.id = p.version_id WHERE p.project_id = ?", (rs, row) -> {
            String slug = rs.getString("slug"); String publicPath = "/a/" + slug;
            return new Publication(slug, rs.getObject("version_id", UUID.class), rs.getLong("version_no"), rs.getString("status"), rs.getLong("revision"), publicPath, origin.isEmpty() ? null : origin + publicPath);
        }, projectId);
        return rows.isEmpty() ? new Publication(null, null, null, "UNPUBLISHED", 0, null, null) : rows.get(0);
    }
    private void requireRevision(Publication current, long expected) {
        if (current.revision() != expected || current.revision() >= MAX_REVISION)
            throw new ApiException(409, "PUBLICATION_CONFLICT", "发布状态已在另一处更新，请刷新后重新确认。");
    }
    private long revision(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() >= MAX_REVISION) throw invalid();
        return value.longValue();
    }
    private String path(String id) { return "/api/projects/" + id + "/publication"; }
    private ApiException invalid() { return new ApiException(422, "INVALID_PUBLICATION", "发布请求必须包含有效版本和非负安全修订号，不能附加其他字段。"); }
    static String publicOrigin(String value) {
        return publicOrigin(value, false);
    }
    private static String publicOrigin(String value, boolean allowInsecureHttp) {
        if (value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            boolean allowedHttp = "http".equals(uri.getScheme()) && (allowInsecureHttp || "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            if ((!"https".equals(uri.getScheme()) && !allowedHttp) || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))
                throw new IllegalArgumentException();
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (IllegalArgumentException error) { throw new IllegalStateException("PUBLIC_APP_ORIGIN must be a valid HTTPS origin; non-loopback HTTP requires explicit demo opt-in"); }
    }
}
