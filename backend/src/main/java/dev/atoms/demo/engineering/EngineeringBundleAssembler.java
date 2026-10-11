package dev.atoms.demo.engineering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.ArtifactValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.TreeMap;

/** Pure assembly: neither a Spring endpoint nor an executor. Authorization belongs to its caller. */
public final class EngineeringBundleAssembler {
    private final ArtifactValidator validator;
    private final ObjectMapper json = new ObjectMapper();

    public EngineeringBundleAssembler(ArtifactValidator validator) { this.validator = validator; }

    public EngineeringBundle assemble(BundleSource source, TemplateSnapshot template) {
        var artifact = validator.parse(json.valueToTree(source.artifact()));
        var files = new ArrayList<>(template.files());
        artifact.files().forEach((path, code) -> files.add(new EngineeringBundle.File(
                "application/" + path, code.getBytes(StandardCharsets.UTF_8))));
        files.add(new EngineeringBundle.File("application/manifest.json", encode(json.valueToTree(artifact.manifest()))));
        files.sort(Comparator.comparing(EngineeringBundle.File::path));

        var manifest = json.createObjectNode();
        manifest.put("bundleFormatVersion", 1).put("templateId", TemplateSnapshot.ID)
                .put("templateVersion", TemplateSnapshot.VERSION).put("sourceVersionId", source.versionId().toString())
                .put("sourceContentHash", source.contentHash()).put("sourceRuntime", artifact.manifest().runtime())
                .put("dataMode", "INITIAL_ONLY");
        var entries = manifest.putArray("files");
        for (var file : files) entries.addObject().put("path", file.path()).put("size", file.size())
                .put("sha256", hash(file.content()));
        String bundleId = hash(encode(manifest));
        manifest.put("bundleId", bundleId);
        files.add(new EngineeringBundle.File("bundle-manifest.json", encode(manifest)));
        files.sort(Comparator.comparing(EngineeringBundle.File::path));
        return new EngineeringBundle(bundleId, files);
    }

    // Format v1 sorts object keys by Java String order; arrays retain their order. This is not RFC 8785.
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            // Jackson 2.21 deprecates fields(); properties() exposes the same object entries.
            // https://github.com/FasterXML/jackson-databind/blob/2.21/src/main/java/com/fasterxml/jackson/databind/JsonNode.java
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            var result = json.createObjectNode();
            for (var entry : sorted.entrySet()) result.set(entry.getKey(), canonical(entry.getValue()));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }
    private byte[] encode(JsonNode node) {
        try { return json.writeValueAsBytes(canonical(node)); }
        catch (JsonProcessingException error) { throw EngineeringBundle.rejected(); }
    }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
