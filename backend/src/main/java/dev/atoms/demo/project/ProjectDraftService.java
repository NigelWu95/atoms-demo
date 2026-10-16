package dev.atoms.demo.project;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectDraftService {
    public record Draft(String prompt, long revision) {}
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    public ProjectDraftService(JdbcTemplate jdbc, ProjectService projects) { this.jdbc = jdbc; this.projects = projects; }
    public Draft get(UUID owner, String projectId) { return read(projects.get(owner, projectId).id()); }
    private Draft read(UUID project) {
        var rows = jdbc.query("SELECT prompt, revision FROM project_drafts WHERE project_id = ?",
                (rs, n) -> new Draft(rs.getString("prompt"), rs.getLong("revision")), project);
        return rows.isEmpty() ? new Draft("", 0) : rows.get(0);
    }
    // The project lock serializes even the first draft insert; source revision is independent.
    // https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
    @Transactional(timeout = 5)
    public Draft save(UUID owner, String projectId, JsonNode input) {
        var project = projects.lock(owner, projectId);
        var expected = input.path("expectedRevision");
        if (!input.isObject() || input.size() != 2 || !input.path("prompt").isTextual()
                || input.path("prompt").asText().length() > 8000 || input.path("prompt").asText().indexOf('\0') >= 0
                || !expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0
                || expected.longValue() >= 9007199254740991L)
            throw new ApiException(422, "INVALID_DRAFT", "草稿需要不超过 8000 字符的 prompt 和有效的 expectedRevision。");
        String prompt = input.path("prompt").asText();
        var current = read(project.id());
        if (current.revision() != expected.longValue()) {
            if (current.revision() == expected.longValue() + 1 && current.prompt().equals(prompt)) return current;
            throw new ApiException(409, "DRAFT_CONFLICT", "草稿已在其他页面修改，请保留当前输入并选择重新读取。");
        }
        if (current.prompt().equals(prompt)) return current;
        long revision = current.revision() + 1;
        jdbc.update("""
                INSERT INTO project_drafts(project_id, prompt, revision) VALUES (?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE SET prompt = excluded.prompt, revision = excluded.revision, updated_at = now()
                """, project.id(), prompt, revision);
        return new Draft(prompt, revision);
    }
}
