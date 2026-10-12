package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Repository-only deterministic source; no HTTP endpoint, platform context, model or database. */
public final class BundleFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Fixture accepts no arguments");
        var json = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var artifact = new Artifact(1, new Artifact.Manifest("独立工程待办", "手工验收夹具，不是 AI 生成", "web-static-v1", 1,
                json.readTree("{\"todos\":[]}")), Map.of("view.html", resource("todo.html"),
                "styles.css", resource("todo.css"), "main.js", resource("todo.js")));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(artifact)));
        var bundle = new EngineeringBundleAssembler(new ArtifactValidator()).assemble(new BundleSource(
                UUID.fromString("37b594a7-b7af-4b16-b823-f65eacb467ae"), hash, artifact), new TemplateCatalog().load());
        System.out.println(json.writeValueAsString(bundle));
    }

    private static String resource(String name) throws Exception {
        try (var input = BundleFixture.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing reviewed source fixture");
            byte[] content = input.readNBytes(120 * 1024 + 1);
            if (content.length > 120 * 1024) throw new IllegalArgumentException("Fixture exceeds source budget");
            return new String(content, StandardCharsets.UTF_8);
        }
    }
}
