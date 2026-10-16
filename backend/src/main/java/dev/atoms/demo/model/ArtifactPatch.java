package dev.atoms.demo.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.artifact.ArtifactValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Patches operate on an immutable, authorized snapshot, never on disk or live state. */
public final class ArtifactPatch {
    private static final Set<String> FILES = Set.of("view.html", "styles.css", "main.js");
    private final ArtifactValidator validator;
    public ArtifactPatch(ArtifactValidator validator) { this.validator = validator; }

    public JsonNode apply(JsonNode base, JsonNode patch) {
        if (base == null || !patch.isObject() || !"artifact-patch-v1".equals(patch.path("format").asText())
                || !patch.path("baseHash").isTextual() || !hash(base).equals(patch.path("baseHash").asText())
                || !allowed(patch, Set.of("format", "baseHash", "edits", "manifest"))) throw invalid();
        var edits = patch.path("edits");
        if (!edits.isArray() || edits.isEmpty() || edits.size() > 24) throw invalid();
        ObjectNode result = base.deepCopy();
        ObjectNode files = (ObjectNode) result.path("files");
        for (var edit : edits) {
            if (!edit.isObject() || edit.size() != 3 || !allowed(edit, Set.of("file", "before", "after"))
                    || !edit.path("file").isTextual() || !FILES.contains(edit.path("file").asText())
                    || !edit.path("before").isTextual() || edit.path("before").asText().isEmpty()
                    || !edit.path("after").isTextual()) throw invalid();
            String file = edit.path("file").asText(), before = edit.path("before").asText();
            String source = files.path(file).asText();
            int offset = source.indexOf(before);
            // Overlapping matches are ambiguous too. Edits are evaluated in order.
            if (offset < 0 || source.indexOf(before, offset + 1) >= 0) throw invalid();
            String updated = source.substring(0, offset) + edit.path("after").asText() + source.substring(offset + before.length());
            if (updated.getBytes(StandardCharsets.UTF_8).length > ArtifactValidator.SOURCE_LIMIT) throw invalid();
            files.put(file, updated);
        }
        if (patch.has("manifest")) {
            var manifest = patch.path("manifest");
            if (!manifest.isObject() || manifest.isEmpty() || !allowed(manifest, Set.of("title", "summary"))) throw invalid();
            manifest.fields().forEachRemaining(entry -> ((ObjectNode) result.path("manifest")).set(entry.getKey(), entry.getValue()));
        }
        validator.parse(result);
        return result;
    }
    public static String hash(JsonNode base) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(base.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private boolean allowed(JsonNode value, Set<String> fields) {
        var names = value.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
    private ModelException invalid() {
        return new ModelException("INVALID_PATCH", "局部修改无效：请使用当前基线哈希、允许的文件和唯一匹配文本，旧版本保持不变。");
    }
}
