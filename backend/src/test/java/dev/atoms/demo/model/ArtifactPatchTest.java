package dev.atoms.demo.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.artifact.ArtifactValidator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ArtifactPatchTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ArtifactPatch patches = new ArtifactPatch(new ArtifactValidator());
    private ObjectNode base() throws Exception {
        return (ObjectNode) json.readTree("""
                {"formatVersion":1,"manifest":{"title":"待办","summary":"可运行","runtime":"web-static-v1","stateSchemaVersion":1,"initialState":{"items":[]}},
                 "files":{"view.html":"<main>待办</main>","styles.css":"main { color: red; }","main.js":"Atoms.ready(() => {});"}}
                """);
    }
    private ObjectNode patch(ObjectNode base, String file, String before, String after) {
        var value = json.createObjectNode().put("format", "artifact-patch-v1").put("baseHash", ArtifactPatch.hash(base));
        value.putArray("edits").addObject().put("file", file).put("before", before).put("after", after);
        return value;
    }
    @Test void mergesOnlyRequestedTextAndKeepsOriginalSnapshotAndInitialState() throws Exception {
        var base = base(); var unchanged = base.deepCopy();
        var patch = patch(base, "styles.css", "color: red", "color: blue");
        var result = patches.apply(base, patch);
        assertThat(result.path("files").path("styles.css").asText()).contains("blue");
        assertThat(result.path("files").path("main.js")).isEqualTo(base.path("files").path("main.js"));
        assertThat(result.path("manifest")).isEqualTo(base.path("manifest"));
        assertThat(base).isEqualTo(unchanged);
    }
    @Test void rejectsWrongBasePathsAmbiguousMissingEmptyMatchesAndUnknownFields() throws Exception {
        var base = base();
        for (String file : new String[]{"../main.js", "pom.xml", "manifest.json"})
            assertThatThrownBy(() -> patches.apply(base, patch(base, file, "x", "y"))).isInstanceOf(ModelException.class);
        for (String before : new String[]{"", "missing", "a"})
            assertThatThrownBy(() -> patches.apply(base, patch(base, "view.html", before, "x"))).isInstanceOf(ModelException.class);
        var wrong = patch(base, "styles.css", "red", "blue").put("baseHash", "wrong");
        assertThatThrownBy(() -> patches.apply(base, wrong)).isInstanceOf(ModelException.class);
        var unknown = patch(base, "styles.css", "red", "blue").put("command", "never-execute");
        assertThatThrownBy(() -> patches.apply(base, unknown)).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> patches.apply(null, wrong)).isInstanceOf(ModelException.class);
    }
    @Test void validatesFinalHtmlSourceSizeAndForbidsStateSchemaEdits() throws Exception {
        var base = base();
        assertThatThrownBy(() -> patches.apply(base, patch(base, "view.html", "待办", "<script>alert(1)</script>"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> patches.apply(base, patch(base, "styles.css", "red", "x".repeat(123000)))).isInstanceOf(RuntimeException.class);
        var changed = patch(base, "styles.css", "red", "blue");
        changed.putObject("manifest").put("stateSchemaVersion", 2);
        assertThatThrownBy(() -> patches.apply(base, changed)).isInstanceOf(ModelException.class);
    }
}
