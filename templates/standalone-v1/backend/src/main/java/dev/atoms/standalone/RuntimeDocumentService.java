package dev.atoms.standalone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Only validated application data reaches the HTML parser; no source is interpolated as markup. */
@Service
final class RuntimeDocumentService {
    private final String document;
    RuntimeDocumentService(RuntimeResources resources, ObjectMapper json) {
        var artifact = resources.artifact();
        try {
            // Preserve the platform's HTML script-data encoding, including CSS closing tags.
            String source = json.writeValueAsString(Map.of("css", artifact.css(), "js", artifact.js()))
                    .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            document = resources.document().formatted(artifact.body(), resources.bootstrap(), source);
        } catch (JsonProcessingException error) { throw ApplicationArtifactLoader.invalid(); }
    }
    String render() { return document; }
}
