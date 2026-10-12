package dev.atoms.standalone;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeResourcesTest {
    static Map<String, byte[]> fixture() throws Exception {
        return TestApplication.resourceFiles(TestApplication.files());
    }
    static RuntimeResources load(Map<String, byte[]> resources) {
        return RuntimeResources.load(path -> resources.containsKey(path) ? new ByteArrayInputStream(resources.get(path)) : null);
    }
    @Test void matchesTheNodePackagingVector() {
        var files = new HashMap<String, byte[]>();
        for (String path : RuntimeResources.PATHS) files.put(path, ApplicationArtifactTest.bytes("content:" + path));
        files.put("runtime/build.txt", ApplicationArtifactTest.bytes("0".repeat(64) + "\nstatic/assets/app.js\nstatic/index.html\n"));
        files.put("static/assets/app.js", ApplicationArtifactTest.bytes("content:static/assets/app.js"));
        files.put("static/index.html", ApplicationArtifactTest.bytes("content:static/index.html"));
        assertThat(RuntimeResources.contentId(files)).isEqualTo("0fa33afd622b8bc672fff7d64aff4a41a67fdebf016bfca4fa4cc04d3c324fbf");
    }
    @Test void loadsPackagedBytesAndRejectsMissingOrStaleFingerprint() throws Exception {
        var files = fixture();
        var loaded = load(files);
        assertThat(loaded.artifact().title()).isEqualTo("计数器");
        assertThat(loaded.bootstrap()).contains("function startAtomsRuntime");
        assertThat(loaded.document()).startsWith("<!doctype html>");
        for (String path : files.keySet()) {
            var missing = new HashMap<>(files); missing.remove(path);
            assertThatThrownBy(() -> load(missing)).isInstanceOf(IllegalArgumentException.class);
            var changed = new HashMap<>(files); changed.put(path, ApplicationArtifactTest.bytes("changed"));
            assertThatThrownBy(() -> load(changed)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void productionClasspathDoesNotFallBackToTestFixtures() {
        assertThatThrownBy(() -> RuntimeResources.fromClasspath(new ClassLoader(null) {})).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid application resources.");
    }
    @Test void limitsReadsEvenWhenResourceSizeIsUnknown() throws Exception {
        var files = fixture();
        files.put("application/main.js", new byte[400 * 1024]);
        assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidStaticIndexesAndRechecksEveryPackagedFrontendByte() throws Exception {
        var original = fixture();
        for (String index : new String[]{"0".repeat(64) + "\n../escape\n", "0".repeat(64) + "\nstatic/index.html\n",
                "0".repeat(64) + "\nstatic/assets/app.js\nstatic/index.html\nstatic/index.html\n", "bad hash\n"}) {
            var files = new HashMap<>(original);
            files.put("runtime/build.txt", ApplicationArtifactTest.bytes(index));
            assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
        }
        var changed = new HashMap<>(original);
        changed.put("static/index.html", ApplicationArtifactTest.bytes("changed shell"));
        assertThatThrownBy(() -> load(changed)).isInstanceOf(IllegalArgumentException.class);
    }
}
