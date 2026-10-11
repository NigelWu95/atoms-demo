package dev.atoms.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ApplicationArtifactTest {
    static Map<String, byte[]> files() {
        var files = new HashMap<String, byte[]>();
        files.put("application/manifest.json", bytes("""
                {"title":"计数器","summary":"独立测试应用","runtime":"web-static-v1",
                 "stateSchemaVersion":1,"initialState":{"count":0}}
                """));
        files.put("application/view.html", bytes("<main><button id=count>0</button></main>"));
        files.put("application/styles.css", bytes("body { color: black; }"));
        files.put("application/main.js", bytes("Atoms.ready(async sdk => { await sdk.storage.get(); });"));
        return files;
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void loadsOnlyFixedFilesAndDefensivelyCopiesState() {
        var input = files();
        var artifact = ApplicationArtifactLoader.parse(input, "a".repeat(64));
        assertThat(artifact.title()).isEqualTo("计数器");
        assertThat(artifact.body()).isEqualTo("<main><button id=\"count\">0</button></main>");
        ((ObjectNode) artifact.initialState()).put("count", 99);
        input.get("application/main.js")[0] = 0;
        assertThat(artifact.initialState().path("count").intValue()).isZero();
        assertThat(artifact.js()).startsWith("Atoms.ready");
        input.put("../escape", bytes("secret"));
        rejects(input);
        for (String path : files().keySet()) { var missing = files(); missing.remove(path); rejects(missing); }
    }

    @Test void rejectsTheSameUnsafeMarkupAsThePlatform() {
        for (String html : List.of("<script>alert(1)</script>", "<img src=x onerror=alert(1)>",
                "<iframe srcdoc=x></iframe>", "<form action='https://example.com'>x</form>",
                "<base href='https://example.com'>", "<meta http-equiv=refresh content=0>",
                "<svg><foreignObject><script>x</script></foreignObject></svg>",
                "<a href='javascript:alert(1)'>x</a>", "<button formaction='/api'>x</button>",
                "<object data='/api'>x</object>", "<input onfocus='alert(1)'>")) {
            var input = files(); input.put("application/view.html", bytes(html)); rejects(input);
        }
    }

    @Test void strictlyValidatesManifestAndUtf8() throws Exception {
        for (String value : List.of("{}", "[]", "null", "{\"title\":1}",
                new String(files().get("application/manifest.json"), StandardCharsets.UTF_8) + "{}")) {
            var input = files(); input.put("application/manifest.json", bytes(value)); rejects(input);
        }
        for (String field : List.of("runtime", "stateSchemaVersion", "initialState", "title", "summary")) {
            var manifest = (ObjectNode) new ObjectMapper().readTree(files().get("application/manifest.json"));
            manifest.put(field, ""); var input = files();
            input.put("application/manifest.json", bytes(manifest.toString())); rejects(input);
        }
        for (byte[] invalid : List.of(new byte[]{(byte) 0xc3, 0x28}, bytes("\0"), bytes("界".repeat(41 * 1024)))) {
            var input = files(); input.put("application/main.js", invalid); rejects(input);
        }
    }

    @Test void rejectsDuplicateKeysDeepAndOversizedInitialState() {
        for (String value : List.of("{\"x\":1,\"x\":2}", "{\"x\":".repeat(65) + "0" + "}".repeat(65),
                "{\"x\":\"" + "a".repeat(256 * 1024) + "\"}")) {
            String manifest = new String(files().get("application/manifest.json"), StandardCharsets.UTF_8)
                    .replace("{\"count\":0}", value);
            var input = files(); input.put("application/manifest.json", bytes(manifest)); rejects(input);
        }
    }
    private void rejects(Map<String, byte[]> input) {
        assertThatThrownBy(() -> ApplicationArtifactLoader.parse(input, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid application resources.");
    }
}
