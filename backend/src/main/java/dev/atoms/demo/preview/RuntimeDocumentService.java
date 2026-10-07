package dev.atoms.demo.preview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeDocumentService {
    private final ArtifactService artifacts;
    private final ProjectService projects;
    private final ArtifactValidator validator;
    private final ObjectMapper json;
    private final String template;
    private final String bootstrap;
    public RuntimeDocumentService(ArtifactService artifacts, ProjectService projects, ArtifactValidator validator, ObjectMapper json) throws IOException {
        this.artifacts = artifacts; this.projects = projects; this.validator = validator; this.json = json;
        template = new ClassPathResource("runtime/document.html").getContentAsString(StandardCharsets.UTF_8);
        bootstrap = new ClassPathResource("runtime/bootstrap.js").getContentAsString(StandardCharsets.UTF_8);
    }
    @Transactional(timeout = 5)
    public String render(UUID owner, String projectId, String versionId, String mode) {
        var project = projects.lock(owner, projectId);
        if (!"CURRENT".equals(mode) && !"HISTORY".equals(mode)) {
            throw new ApiException(422, "INVALID_PREVIEW_MODE", "只支持 CURRENT 或 HISTORY 预览。");
        }
        var version = artifacts.get(owner, projectId, versionId);
        if ("CURRENT".equals(mode) && !version.id().equals(project.currentVersionId())) {
            throw new ApiException(409, "STALE_APP_VERSION", "当前版本已变化，请重新加载预览。");
        }
        var files = version.artifact().files();
        return template.formatted(validator.body(files.get("view.html")), bootstrap, serialize(Map.of("css", files.get("styles.css"), "js", files.get("main.js"))));
    }
    private String serialize(Object value) {
        try {
            // An HTML parser sees </script> even inside JS strings. Escape data BEFORE placing it into a script.
            return json.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e")
                    .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (JsonProcessingException error) { throw new IllegalStateException("Runtime source cannot be encoded"); }
    }
}
