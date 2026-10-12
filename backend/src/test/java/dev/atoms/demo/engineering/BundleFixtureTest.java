package dev.atoms.demo.engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BundleFixtureTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void defaultFixtureIsDeterministicAndKeepsEmptyInitialState() throws Exception {
        var original = fixture();
        assertThat(fixture()).isEqualTo(original);
        assertThat(json.readTree(file(original, "application/manifest.json")).path("initialState").toString())
                .isEqualTo("{\"todos\":[]}");
        assertThat(new String(file(original, "application/view.html"), StandardCharsets.UTF_8))
                .contains("<h1>把想法，一件件完成。</h1>");
    }

    @Test void upgradedFixtureChangesOnlyHtmlAndManifestWithoutChangingProvenanceVersion() throws Exception {
        var original = fixture();
        var upgraded = fixture("upgraded");
        assertThat(fixture("upgraded")).isEqualTo(upgraded);
        assertThat(upgraded.bundleId()).isNotEqualTo(original.bundleId());
        assertThat(upgraded.files()).extracting(EngineeringBundle.File::path)
                .containsExactlyElementsOf(original.files().stream().map(EngineeringBundle.File::path).toList());
        assertThat(upgraded.files().stream().filter(entry -> !Arrays.equals(entry.content(), file(original, entry.path())))
                .map(EngineeringBundle.File::path)).containsExactly("application/view.html", "bundle-manifest.json");
        var before = json.readTree(file(original, "bundle-manifest.json"));
        var after = json.readTree(file(upgraded, "bundle-manifest.json"));
        assertThat(after.path("sourceVersionId")).isEqualTo(before.path("sourceVersionId"));
        assertThat(after.path("sourceContentHash")).isNotEqualTo(before.path("sourceContentHash"));
        assertThat(new String(file(upgraded, "application/view.html"), StandardCharsets.UTF_8))
                .isEqualTo(new String(file(original, "application/view.html"), StandardCharsets.UTF_8)
                        .replace("<h1>把想法，一件件完成。</h1>", "<h1>把想法，一件件完成（升级版）。</h1>"));
    }

    @Test void refusesUnreviewedFixtureNamesAndExtraArguments() {
        for (var args : new String[][] {{"../todo.html"}, {"todo"}, {"--skip-tests"}, {"upgraded", "extra"}}) {
            assertThatThrownBy(() -> fixture(args)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private EngineeringBundle fixture(String... args) throws Exception {
        var output = new ByteArrayOutputStream();
        var previous = System.out;
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            BundleFixture.main(args);
        } finally { System.setOut(previous); }
        return json.readValue(output.toByteArray(), EngineeringBundle.class);
    }

    private byte[] file(EngineeringBundle bundle, String path) {
        return bundle.files().stream().filter(entry -> entry.path().equals(path)).findFirst().orElseThrow().content();
    }
}
