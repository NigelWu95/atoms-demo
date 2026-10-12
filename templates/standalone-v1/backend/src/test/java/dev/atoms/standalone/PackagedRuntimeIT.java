package dev.atoms.standalone;

import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Runs after package, against the actual executable JAR, never target/test-classes. */
class PackagedRuntimeIT {
    @Test void packagedRuntimeMatchesTheBuildIdentityAndContainsNoTestEntryPoint() throws Exception {
        try (var jar = new ZipFile(Path.of("target/app.jar").toFile())) {
            assertThat(jar.getEntry("BOOT-INF/classes/dev/atoms/standalone/StandaloneApplication.class")).isNotNull();
            assertThat(jar.getEntry("BOOT-INF/classes/dev/atoms/standalone/TestApplication.class")).isNull();
            assertThat(jar.getEntry("BOOT-INF/classes/dev/atoms/standalone/TestDatabase.class")).isNull();
            assertThat(jar.getEntry("BOOT-INF/classes/db/migration/V003__idempotency.sql")).isNotNull();
            String expected = System.getProperty("standalone.expected-runtime-id");
            if (expected == null) {
                // Backend-only verify is supported but intentionally is not a runnable app build.
                assertThat(jar.getEntry("BOOT-INF/classes/runtime/content.sha256")).isNull();
                return;
            }
            assertThat(expected).matches("[a-f0-9]{64}");
            var resources = RuntimeResources.load(path -> {
                var entry = jar.getEntry("BOOT-INF/classes/" + path);
                try { return entry == null ? null : jar.getInputStream(entry); }
                catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot read packaged resource."); }
            });
            assertThat(resources.artifact().loadedRuntimeId()).isEqualTo(expected);
        }
    }
}
