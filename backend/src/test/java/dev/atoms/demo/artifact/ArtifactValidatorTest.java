package dev.atoms.demo.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ArtifactValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ArtifactValidator validator = new ArtifactValidator();

    static ObjectNode source(ObjectMapper json) {
        var source = json.createObjectNode().put("formatVersion", 1);
        source.putObject("manifest").put("title", "测试计数器").put("summary", "手工测试产物，不是 AI 生成")
                .put("runtime", "web-static-v1").put("stateSchemaVersion", 1).putObject("initialState").put("count", 0);
        source.putObject("files").put("view.html", "<main><h1>测试计数器</h1><button id=\"count\">0</button></main>")
                .put("styles.css", "body { font-family: system-ui; }")
                .put("main.js", "document.querySelector('#count').onclick = e => e.target.textContent = Number(e.target.textContent) + 1;");
        return source;
    }

    @Test void acceptsOnlyTheFrozenShapeAndPreservesSource() {
        var input = source(json);
        assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(validator.parse(input))).isEqualTo(input);
        for (String field : List.of("../escape", "manifest.json", "index.html")) {
            var invalid = input.deepCopy();
            ((ObjectNode) invalid.path("files")).put(field, "bad");
            assertThatThrownBy(() -> validator.parse(invalid)).isInstanceOf(ApiException.class);
        }
        var wrong = input.deepCopy();
        wrong.put("formatVersion", 1.5);
        assertThatThrownBy(() -> validator.parse(wrong)).isInstanceOf(ApiException.class);
        ((ObjectNode) input.path("manifest")).put("stateSchemaVersion", 2);
        assertThatThrownBy(() -> validator.parse(input)).isInstanceOf(ApiException.class);
    }

    @Test void rejectsUnsafeMarkupInsteadOfSilentlyRemovingIt() {
        for (String html : List.of("<script>alert(1)</script>", "<img src=x onerror=alert(1)>",
                "<iframe srcdoc=x></iframe>", "<form action='https://example.com'>x</form>",
                "<base href='https://example.com'>", "<meta http-equiv=refresh content=0>",
                "<svg><foreignObject><script>x</script></foreignObject></svg>",
                "<a href='javascript:alert(1)'>x</a>", "<button formaction='/api'>x</button>",
                "<object data='/api'>x</object>")) {
            var input = source(json);
            ((ObjectNode) input.path("files")).put("view.html", html);
            assertThatThrownBy(() -> validator.parse(input)).as(html).isInstanceOf(ApiException.class);
        }
    }

    @Test void capsUtf8SourceBytesAndInitialState() {
        var input = source(json);
        ((ObjectNode) input.path("files")).put("main.js", "界".repeat(41 * 1024));
        assertThatThrownBy(() -> validator.parse(input)).isInstanceOf(ApiException.class);
        var oversized = source(json);
        ((ObjectNode) oversized.path("manifest").path("initialState")).put("big", "x".repeat(256 * 1024));
        assertThatThrownBy(() -> validator.parse(oversized)).isInstanceOf(ApiException.class);
    }
}
