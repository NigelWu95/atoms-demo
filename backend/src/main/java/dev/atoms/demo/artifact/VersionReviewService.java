package dev.atoms.demo.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VersionReviewService {
    public record Change(String file, String before, String after) {}
    public record Review(UUID versionId, UUID baseVersionId, Long baseVersionNo, UUID restoredFromId,
                         UUID runId, String request, List<Change> changes) {}
    private record Request(UUID id, String prompt) {}
    private final ArtifactService artifacts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public VersionReviewService(ArtifactService artifacts, JdbcTemplate jdbc, ObjectMapper json) {
        this.artifacts = artifacts; this.jdbc = jdbc; this.json = json;
    }
    public Review get(UUID owner, String project, String id) {
        var version = artifacts.get(owner, project, id);
        var base = version.basedOnId() == null ? null : artifacts.get(owner, project, version.basedOnId().toString());
        var requests = jdbc.query("SELECT id, prompt FROM runs WHERE owner_id = ? AND project_id = ? AND result_version_id = ? AND status = 'SUCCEEDED' ORDER BY ordinal DESC LIMIT 1",
                (rs, n) -> new Request(rs.getObject("id", UUID.class), rs.getString("prompt")), owner, version.projectId(), version.id());
        var changes = new ArrayList<Change>();
        for (String file : List.of("view.html", "styles.css", "main.js", "manifest.json")) {
            String before = base == null ? "" : source(base.artifact(), file);
            String after = source(version.artifact(), file);
            if (!before.equals(after)) changes.add(new Change(file, before, after));
        }
        return new Review(version.id(), version.basedOnId(), base == null ? null : base.versionNo(), version.restoredFromId(),
                requests.isEmpty() ? null : requests.get(0).id(), requests.isEmpty() ? null : requests.get(0).prompt(), List.copyOf(changes));
    }
    private String source(Artifact artifact, String file) {
        return file.equals("manifest.json") ? json.valueToTree(artifact.manifest()).toPrettyString() : artifact.files().get(file);
    }
}
