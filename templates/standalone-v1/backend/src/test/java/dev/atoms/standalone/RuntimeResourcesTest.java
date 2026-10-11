package dev.atoms.standalone;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeResourcesTest {
    static Map<String, byte[]> fixture() throws Exception {
        var resources = ApplicationArtifactTest.files();
        for (String path : new String[]{"runtime/bootstrap.js", "runtime/document.html"}) {
            try (var stream = RuntimeResourcesTest.class.getClassLoader().getResourceAsStream(path)) {
                resources.put(path, stream.readAllBytes());
            }
        }
        resources.put("runtime/content.sha256", ApplicationArtifactTest.bytes(RuntimeResources.contentId(resources) + "\n"));
        return resources;
    }
    static RuntimeResources load(Map<String, byte[]> resources) {
        return RuntimeResources.load(path -> resources.containsKey(path) ? new ByteArrayInputStream(resources.get(path)) : null);
    }
    @Test void matchesTheNodePackagingVector() {
        var files = new HashMap<String, byte[]>();
        for (String path : RuntimeResources.PATHS) files.put(path, ApplicationArtifactTest.bytes("content:" + path));
        assertThat(RuntimeResources.contentId(files)).isEqualTo("f9eda96f9b15e0936f229edf0b5aebfc0d198f26adeb150e67155bd900eef69d");
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
}
