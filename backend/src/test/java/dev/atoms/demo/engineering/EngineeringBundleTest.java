package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.atoms.demo.artifact.Artifact;
import dev.atoms.demo.artifact.ArtifactValidator;
import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EngineeringBundleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EngineeringBundleAssembler assembler = new EngineeringBundleAssembler(new ArtifactValidator());
    private static final UUID VERSION = UUID.fromString("37b594a7-b7af-4b16-b823-f65eacb467ae");
    private static final String SOURCE_HASH = "ab".repeat(32);

    @Test void assemblesFixedApplicationFilesWithIndependentlyVerifiableHashes() throws Exception {
        var bundle = assembler.assemble(source(), template());
        assertThat(bundle.files()).extracting(EngineeringBundle.File::path).containsExactly(
                "README.md", "application/main.js", "application/manifest.json", "application/styles.css",
                "application/view.html", "backend/pom.xml", "bundle-manifest.json");
        var manifest = json.readTree(file(bundle, "bundle-manifest.json"));
        assertThat(manifest.path("bundleFormatVersion").intValue()).isEqualTo(1);
        assertThat(manifest.path("templateId").asText()).isEqualTo("vue-spring-postgres");
        assertThat(manifest.path("templateVersion").asText()).isEqualTo("1.0.0");
        assertThat(manifest.path("sourceVersionId").asText()).isEqualTo(VERSION.toString());
        assertThat(manifest.path("sourceContentHash").asText()).isEqualTo(SOURCE_HASH);
        assertThat(manifest.path("sourceRuntime").asText()).isEqualTo("web-static-v1");
        assertThat(manifest.path("dataMode").asText()).isEqualTo("INITIAL_ONLY");
        assertThat(manifest.path("bundleId").asText()).isEqualTo(bundle.bundleId());
        var identity = ((ObjectNode) manifest).deepCopy();
        identity.remove("bundleId");
        assertThat(bundle.bundleId()).isEqualTo(sha256(json.writeValueAsBytes(identity)));
        assertThat(manifest.path("files")).hasSize(6);
        for (var entry : manifest.path("files")) {
            byte[] bytes = file(bundle, entry.path("path").asText());
            assertThat(entry.path("size").longValue()).isEqualTo(bytes.length);
            assertThat(entry.path("sha256").asText()).isEqualTo(sha256(bytes));
        }
        assertThat(json.readTree(file(bundle, "application/manifest.json")))
                .isEqualTo(json.valueToTree(source().artifact().manifest()));
        assertThat(file(bundle, "application/main.js")).isEqualTo("// reviewed fixture".getBytes(StandardCharsets.UTF_8));
    }

    @Test void canonicalizesJsonObjectsAndFileOrderButPreservesArrayOrder() throws Exception {
        var first = source();
        var reversed = new Artifact(1, new Artifact.Manifest("测试", "受控夹具", "web-static-v1", 1,
                json.readTree("{\"a\":{\"a\":1,\"z\":2},\"z\":[2,1]}")), first.artifact().files());
        var one = assembler.assemble(first, template());
        var two = assembler.assemble(new BundleSource(VERSION, SOURCE_HASH, reversed),
                new TemplateSnapshot(Set.of("README.md", "backend/pom.xml"),
                        List.of(new EngineeringBundle.File("README.md", new byte[]{1}),
                                new EngineeringBundle.File("backend/pom.xml", new byte[]{2}))));
        assertThat(two.bundleId()).isEqualTo(one.bundleId());
        for (var entry : one.files()) assertThat(file(two, entry.path())).isEqualTo(entry.content());
        var initial = json.readTree(file(one, "application/manifest.json")).path("initialState");
        assertThat(initial.path("z").toString()).isEqualTo("[2,1]");
        assertThat(new String(file(one, "application/manifest.json"), StandardCharsets.UTF_8))
                .contains("\"a\":{\"a\":1,\"z\":2},\"z\":[2,1]");
    }

    @Test void revalidatesHistoricalSourceRatherThanExportingUnsafeMarkup() throws Exception {
        var files = new LinkedHashMap<>(source().artifact().files());
        files.put("view.html", "<script>bad()</script>");
        var unsafe = new Artifact(1, source().artifact().manifest(), files);
        assertThatThrownBy(() -> assembler.assemble(new BundleSource(VERSION, SOURCE_HASH, unsafe), template()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ARTIFACT");
    }

    @Test void snapshotsMutableSourceAndNeverExposesOwnedBytesOrCollections() throws Exception {
        var initial = json.createObjectNode().put("count", 0);
        var sourceFiles = new LinkedHashMap<>(source().artifact().files());
        var frozen = new BundleSource(VERSION, SOURCE_HASH,
                new Artifact(1, new Artifact.Manifest("测试", "受控夹具", "web-static-v1", 1, initial), sourceFiles));
        byte[] templateBytes = {1};
        var entry = new EngineeringBundle.File("README.md", templateBytes);
        var entries = new java.util.ArrayList<>(List.of(entry));
        var template = new TemplateSnapshot(Set.of("README.md"), entries);
        var before = assembler.assemble(frozen, template);
        initial.put("count", 99);
        sourceFiles.put("main.js", "bad");
        ((ObjectNode) frozen.artifact().manifest().initialState()).put("count", 77);
        templateBytes[0] = 2;
        entry.content()[0] = 3;
        entries.clear();
        file(before, "README.md")[0] = 4;
        assertThat(assembler.assemble(frozen, template).bundleId()).isEqualTo(before.bundleId());
        assertThat(file(before, "README.md")).containsExactly((byte) 1);
        assertThatThrownBy(() -> before.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> template.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.artifact().files().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void identitiesChangeForSourceProvenanceAndTemplateOrApplicationBytes() throws Exception {
        var source = source();
        var before = assembler.assemble(source, template()).bundleId();
        assertThat(assembler.assemble(new BundleSource(new UUID(0, 1), SOURCE_HASH, source.artifact()), template()).bundleId())
                .isNotEqualTo(before);
        assertThat(assembler.assemble(new BundleSource(VERSION, "cd".repeat(32), source.artifact()), template()).bundleId())
                .isNotEqualTo(before);
        var changed = new LinkedHashMap<>(source.artifact().files());
        changed.put("main.js", "// modified");
        assertThat(assembler.assemble(new BundleSource(VERSION, SOURCE_HASH,
                new Artifact(1, source.artifact().manifest(), changed)), template()).bundleId()).isNotEqualTo(before);
        assertThat(assembler.assemble(source, new TemplateSnapshot(Set.of("README.md", "backend/pom.xml"),
                List.of(new EngineeringBundle.File("README.md", new byte[]{3}),
                        new EngineeringBundle.File("backend/pom.xml", new byte[]{2})))).bundleId()).isNotEqualTo(before);
    }

    @Test void sourceStringsStayInApplicationDataAndCannotReplaceTemplateCommands() throws Exception {
        var manifest = source().artifact().manifest();
        String title = "../pom.xml $(touch CANARY_DO_NOT_EXECUTE)";
        var source = new BundleSource(VERSION, SOURCE_HASH, new Artifact(1,
                new Artifact.Manifest(title, manifest.summary(), manifest.runtime(), 1, manifest.initialState()), source().artifact().files()));
        var bundle = assembler.assemble(source, template());
        assertThat(json.readTree(file(bundle, "application/manifest.json")).path("title").asText()).isEqualTo(title);
        assertThat(file(bundle, "backend/pom.xml")).containsExactly((byte) 2);
        assertThat(bundle.files()).extracting(EngineeringBundle.File::path).noneMatch(path -> path.contains("CANARY"));
    }

    @Test void rejectsInvalidSourceIdentityAndArtifactsWithoutReadingEnvironment() throws Exception {
        var artifact = source().artifact();
        assertThatThrownBy(() -> new BundleSource(null, SOURCE_HASH, artifact)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BundleSource(VERSION, "invalid", artifact)).isInstanceOf(IllegalArgumentException.class);
        var invalid = new Artifact(2, artifact.manifest(), artifact.files());
        assertThatThrownBy(() -> assembler.assemble(new BundleSource(VERSION, SOURCE_HASH, invalid), template()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_ARTIFACT");
    }

    @Test void defensiveFileCopiesRetainValueEquality() {
        var file = new EngineeringBundle.File("README.md", new byte[]{1, 2});
        var copy = new EngineeringBundle.File(file.path(), file.content());
        assertThat(copy).isEqualTo(file).hasSameHashCodeAs(file);
        assertThat(new EngineeringBundle.File(file.path(), new byte[]{1, 3})).isNotEqualTo(file);
    }

    private BundleSource source() throws Exception {
        return new BundleSource(VERSION, SOURCE_HASH, new Artifact(1,
                new Artifact.Manifest("测试", "受控夹具", "web-static-v1", 1,
                        json.readTree("{\"z\":[2,1],\"a\":{\"z\":2,\"a\":1}}")),
                Map.of("view.html", "<main>测试</main>", "styles.css", "body { color: red; }", "main.js", "// reviewed fixture")));
    }
    private TemplateSnapshot template() {
        return new TemplateSnapshot(Set.of("README.md", "backend/pom.xml"),
                List.of(new EngineeringBundle.File("backend/pom.xml", new byte[]{2}),
                        new EngineeringBundle.File("README.md", new byte[]{1})));
    }
    private byte[] file(EngineeringBundle bundle, String path) {
        return bundle.files().stream().filter(file -> file.path().equals(path)).findFirst().orElseThrow().content();
    }
    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
