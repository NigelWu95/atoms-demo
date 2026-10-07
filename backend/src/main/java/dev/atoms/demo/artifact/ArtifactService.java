package dev.atoms.demo.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactService {
    public record Version(UUID id, UUID projectId, long versionNo, String contentHash, UUID basedOnId, Instant createdAt, Artifact artifact) {}
    public record Summary(UUID id, UUID projectId, long versionNo, String contentHash, UUID basedOnId, Instant createdAt) {}
    public record Page(List<Summary> items, String nextCursor) {}
    private static final RowMapper<Summary> SUMMARY = (rs, row) -> new Summary(rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getLong("version_no"), rs.getString("content_hash"),
            rs.getObject("based_on_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final ArtifactValidator validator;
    private final ObjectMapper json;
    public ArtifactService(JdbcTemplate jdbc, ProjectService projects, ArtifactValidator validator, ObjectMapper json) {
        this.jdbc = jdbc; this.projects = projects; this.validator = validator; this.json = json;
    }

    /** Internal worker seam only. No production HTTP source-write endpoint. No upstream I/O in this transaction. */
    @Transactional(timeout = 5)
    public Version save(UUID owner, String projectId, long expectedRevision, UUID baseVersionId, JsonNode source) {
        var artifact = validator.parse(source);
        var project = projects.lock(owner, projectId);
        if (project.projectRevision() != expectedRevision || !Objects.equals(project.currentVersionId(), baseVersionId)) {
            throw new ApiException(409, "BASE_VERSION_CONFLICT", "项目版本已经变化，请重新加载。");
        }
        // Row lock serializes numbering AND the pointer transition. Hash deliberately has no unique constraint.
        // https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS
        long number = jdbc.queryForObject("SELECT coalesce(max(version_no), 0) + 1 FROM versions WHERE project_id = ?", Long.class, project.id());
        UUID id = UUID.randomUUID();
        String encoded = encode(artifact);
        jdbc.update("INSERT INTO versions(id, project_id, version_no, source_json, content_hash, based_on_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, project.id(), number, encoded, hash(encoded), baseVersionId);
        jdbc.update("UPDATE projects SET current_version_id = ?, project_revision = project_revision + 1, updated_at = now() WHERE id = ?",
                id, project.id());
        return get(owner, projectId, id.toString());
    }

    public Version get(UUID owner, String projectId, String versionId) {
        var project = projects.get(owner, projectId);
        UUID id = versionId(versionId);
        var versions = jdbc.query("SELECT * FROM versions WHERE project_id = ? AND id = ?", (rs, row) -> {
            var summary = SUMMARY.mapRow(rs, row);
            return new Version(summary.id(), summary.projectId(), summary.versionNo(), summary.contentHash(),
                    summary.basedOnId(), summary.createdAt(), decode(rs.getString("source_json")));
        }, project.id(), id);
        if (versions.isEmpty()) throw missing();
        return versions.get(0);
    }
    public Page list(UUID owner, String projectId, String cursor, int limit) {
        var project = projects.get(owner, projectId);
        if (limit < 1 || limit > 100) throw new ApiException(422, "INVALID_INPUT", "limit 必须在 1–100 之间。");
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            try {
                before = Long.parseLong(cursor);
                if (before < 1 || !Long.toString(before).equals(cursor)) throw new NumberFormatException();
            } catch (NumberFormatException error) { throw new ApiException(400, "INVALID_CURSOR", "版本分页位置无效。"); }
        }
        var rows = jdbc.query("""
                SELECT id, project_id, version_no, content_hash, based_on_id, created_at FROM versions
                WHERE project_id = ? AND version_no < ? ORDER BY version_no DESC LIMIT ?
                """, SUMMARY, project.id(), before, limit + 1);
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, rows.size() > limit ? Long.toString(items.get(items.size() - 1).versionNo()) : null);
    }
    private UUID versionId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw missing();
            return id;
        } catch (IllegalArgumentException error) { throw missing(); }
    }
    private Artifact decode(String value) {
        try { return json.readValue(value, Artifact.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Stored artifact cannot be decoded"); }
    }
    private String encode(Artifact artifact) {
        try { return json.writeValueAsString(artifact); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Artifact cannot be encoded"); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ApiException missing() { return new ApiException(404, "VERSION_NOT_FOUND", "版本不存在或无权访问。"); }
}
