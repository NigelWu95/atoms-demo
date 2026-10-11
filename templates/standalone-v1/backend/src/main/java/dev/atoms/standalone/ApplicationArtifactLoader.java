package dev.atoms.standalone;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Fixed application inputs only. Invalid or missing resources never become an empty demo. */
final class ApplicationArtifactLoader {
    static final int SOURCE_LIMIT = 120 * 1024;
    static final int STATE_LIMIT = 256 * 1024;
    static final Set<String> FILES = Set.of("application/manifest.json", "application/view.html",
            "application/styles.css", "application/main.js");

    static Artifact parse(Map<String, byte[]> files, String runtimeId) {
        if (!files.keySet().equals(FILES) || runtimeId == null || !runtimeId.matches("[a-f0-9]{64}")) throw invalid();
        int sourceBytes = 0;
        for (String path : FILES) {
            byte[] bytes = files.get(path);
            if (bytes == null) throw invalid();
            if (path.endsWith("manifest.json")) {
                if (bytes.length > STATE_LIMIT + 16 * 1024) throw invalid();
            } else {
                if (bytes.length > SOURCE_LIMIT) throw invalid();
                sourceBytes += bytes.length;
            }
        }
        if (sourceBytes > SOURCE_LIMIT) throw invalid();
        var json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        // https://github.com/FasterXML/jackson-core/blob/2.21/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build());
        try {
            JsonNode manifest = json.readTree(utf8(files.get("application/manifest.json")));
            var fields = new HashSet<String>();
            if (manifest == null || !manifest.isObject()) throw invalid();
            manifest.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("title", "summary", "runtime", "stateSchemaVersion", "initialState"))
                    || !manifest.path("runtime").isTextual() || !"web-static-v1".equals(manifest.path("runtime").textValue())
                    || !manifest.path("stateSchemaVersion").isIntegralNumber()
                    || !manifest.path("stateSchemaVersion").canConvertToInt()
                    || manifest.path("stateSchemaVersion").intValue() != 1) throw invalid();
            var initial = manifest.path("initialState");
            if (!initial.isObject() || json.writeValueAsBytes(initial).length > STATE_LIMIT) throw invalid();
            return new Artifact(text(manifest.path("title"), 120), text(manifest.path("summary"), 2000),
                    initial, runtimeId, new ApplicationHtml().normalize(utf8(files.get("application/view.html"))),
                    utf8(files.get("application/styles.css")), utf8(files.get("application/main.js")));
        } catch (IOException error) { throw invalid(); }
    }

    static String utf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0) throw invalid();
            return value;
        } catch (CharacterCodingException error) { throw invalid(); }
    }
    private static String text(JsonNode node, int max) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max
                || node.textValue().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return node.textValue();
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid application resources."); }

    record Artifact(String title, String summary, JsonNode initialState, String loadedRuntimeId,
                    String body, String css, String js) {
        Artifact { initialState = initialState.deepCopy(); }
        @Override public JsonNode initialState() { return initialState.deepCopy(); }
    }
}
