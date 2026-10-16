package dev.atoms.demo.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.atoms.demo.project.ProjectService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IterationContext {
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final ObjectMapper json;
    public IterationContext(JdbcTemplate jdbc, ProjectService projects, ObjectMapper json) {
        this.jdbc = jdbc; this.projects = projects; this.json = json;
    }
    public ArrayNode history(UUID owner, String projectId, UUID base) {
        var project = projects.get(owner, projectId);
        var result = json.createArrayNode();
        if (base == null) return result;
        // Restored snapshots inherit requirements from the restored branch, not the discarded one.
        var rows = jdbc.query("""
                WITH RECURSIVE lineage AS (
                    SELECT id, project_id, based_on_id, restored_from_id, 0 AS depth FROM versions WHERE project_id = ? AND id = ?
                    UNION ALL
                    SELECT v.id, v.project_id, v.based_on_id, v.restored_from_id, l.depth + 1
                    FROM versions v JOIN lineage l ON v.id = coalesce(l.restored_from_id, l.based_on_id)
                    AND v.project_id = l.project_id WHERE l.depth < 63
                )
                SELECT r.result_version_id, r.prompt FROM lineage l JOIN runs r ON r.result_version_id = l.id
                AND r.project_id = l.project_id WHERE r.owner_id = ? AND r.status = 'SUCCEEDED'
                AND l.restored_from_id IS NULL ORDER BY l.depth, r.ordinal DESC LIMIT 8
                """, (rs, n) -> json.createObjectNode().put("versionId", rs.getString("result_version_id"))
                        .put("request", rs.getString("prompt")), project.id(), base, owner);
        int remaining = 12000;
        for (var row : rows) {
            if (remaining == 0) break;
            String prompt = row.path("request").asText();
            int kept = Math.min(Math.min(prompt.length(), 2400), remaining);
            if (kept < prompt.length() && kept > 0 && Character.isHighSurrogate(prompt.charAt(kept - 1))) kept--;
            row.put("request", prompt.substring(0, kept)).put("truncated", kept < prompt.length());
            remaining -= kept; result.insert(0, row);
        }
        return result;
    }
}
