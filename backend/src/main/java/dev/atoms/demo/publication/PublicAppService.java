package dev.atoms.demo.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.preview.RuntimeDocumentService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicAppService {
    /** Internal capability, never serialize it from a controller: it contains the private project id. */
    public record LiveVersion(UUID projectId, UUID versionId, long versionNo, Artifact artifact) {
        public void requireVersion(String expected) {
            if (!versionId.toString().equals(expected)) throw new ApiException(409, "STALE_APP_VERSION", "应用已发布新版本，请重新加载应用后再操作。");
        }
    }
    public record Metadata(String slug, UUID versionId, long versionNo, String title, String summary, int stateSchemaVersion, JsonNode initialState) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RuntimeDocumentService documents;
    public PublicAppService(JdbcTemplate jdbc, ObjectMapper json, RuntimeDocumentService documents) {
        this.jdbc = jdbc; this.json = json; this.documents = documents;
    }
    @Transactional(timeout = 5)
    public Metadata metadata(String slug) {
        var live = lock(slug); var manifest = live.artifact().manifest();
        return new Metadata(slug, live.versionId(), live.versionNo(), manifest.title(), manifest.summary(), manifest.stateSchemaVersion(), manifest.initialState());
    }
    @Transactional(timeout = 5)
    public String document(String slug, String version) {
        var live = lock(slug); live.requireVersion(version);
        return documents.renderArtifact(live.artifact());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public LiveVersion lock(String slug) {
        if (!slug.matches("[0-9a-f]{32}")) throw missing();
        var ids = jdbc.query("SELECT project_id FROM publications WHERE slug = ?", (rs, row) -> rs.getObject("project_id", UUID.class), slug);
        if (ids.isEmpty()) throw missing();
        // Same first lock as publishing/source/state writes; re-read the pointer AFTER acquiring it.
        // https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", UUID.class, ids.get(0));
        var rows = jdbc.query("""
                SELECT p.project_id, p.version_id, v.version_no, v.source_json FROM publications p
                JOIN versions v ON v.id = p.version_id AND v.project_id = p.project_id
                WHERE p.slug = ? AND p.status = 'PUBLISHED'
                """, (rs, row) -> new LiveVersion(rs.getObject("project_id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getLong("version_no"), decode(rs.getString("source_json"))), slug);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }
    private Artifact decode(String value) {
        try { return json.readValue(value, Artifact.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Published artifact cannot be decoded"); }
    }
    private ApiException missing() { return new ApiException(404, "APP_NOT_PUBLISHED", "应用未发布或已下线。"); }
}
