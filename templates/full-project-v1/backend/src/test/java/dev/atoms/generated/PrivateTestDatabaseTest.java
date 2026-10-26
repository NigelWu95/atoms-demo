package dev.atoms.generated;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PrivateTestDatabaseTest {
    @TempDir Path temporary;

    @Test void acceptsExplicitPortableNamesWithoutExposingCredentials() throws Exception {
        Path file = config(Map.of());
        var loaded = PrivateTestDatabase.read(project(), file.toString());
        assertThat(loaded.toString()).doesNotContain("RUNTIME_CANARY", file.toString(), "portable_test");
    }

    @Test void rejectsRuntimeTargetsExtraKeysAndConnectionInjection() throws Exception {
        for (var change : List.of(Map.of("purpose", "runtime"), Map.of("schema", "public"),
                Map.of("database", "postgres"), Map.of("user", "atoms_test"), Map.of("bindingId", "1-1-1-1-1"),
                Map.of("port", "18181"), Map.of("spring.config.import", "file:/private/secret"),
                Map.of("password", "${APP_DB_PASSWORD}"), Map.of("jdbcUrl", "jdbc:postgresql://elsewhere/portable_test"),
                Map.of("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/portable_test?currentSchema=generated_app&options=bad")))
            reject(config(change));
    }

    @Test void rejectsDuplicateKeysOversizeFilesAndSourceTreeConfiguration() throws Exception {
        Path duplicate = config(Map.of());
        Files.writeString(duplicate, "\nuser=another_role\n", StandardOpenOption.APPEND);
        reject(duplicate);
        Path large = config(Map.of()); Files.writeString(large, "#".repeat(16385)); reject(large);
        Path embedded = project().resolve("test.properties");
        Files.copy(config(Map.of()), embedded); privateMode(embedded); reject(embedded);
        assertThatThrownBy(() -> PrivateTestDatabase.read(project(), "relative.properties"))
                .hasMessage("Explicit private test database configuration required").hasNoCause();
    }

    @Test void rejectsPublicLinkedAndChangedConfiguration() throws Exception {
        Path publicFile = config(Map.of());
        Files.setPosixFilePermissions(publicFile, PosixFilePermissions.fromString("rw-r--r--")); reject(publicFile);
        Path target = config(Map.of()); Path symbolic = target.resolveSibling("symbolic.properties");
        Files.createSymbolicLink(symbolic, target); reject(symbolic);
        Files.createLink(target.resolveSibling("hardlink.properties"), target); reject(target);
        Path changed = config(Map.of());
        var loaded = PrivateTestDatabase.read(project(), changed.toString());
        Files.writeString(changed, "\n# changed\n", StandardOpenOption.APPEND);
        assertThatThrownBy(loaded::verifyFile).hasMessage("Explicit private test database configuration required").hasNoCause();
    }

    private Path project() throws Exception { return Files.createDirectories(temporary.toRealPath().resolve("project")); }
    private void reject(Path file) {
        assertThatThrownBy(() -> PrivateTestDatabase.read(project(), file.toString()))
                .hasMessage("Explicit private test database configuration required").hasNoCause();
    }
    private Path config(Map<String, String> changes) throws Exception {
        var values = new Properties();
        values.setProperty("purpose", "test"); values.setProperty("database", "portable_test");
        values.setProperty("user", "portable_role"); values.setProperty("password", "RUNTIME_CANARY");
        values.setProperty("schema", "generated_app");
        values.setProperty("bindingId", "22163841-f1a3-4ae9-a2fc-2aa6aa52b067");
        values.setProperty("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/portable_test?currentSchema=generated_app");
        values.putAll(changes);
        Path file = Files.createTempFile(temporary.toRealPath(), "test-", ".properties");
        privateMode(file);
        try (var output = Files.newOutputStream(file)) { values.store(output, "synthetic test fixture"); }
        return file;
    }
    private static void privateMode(Path file) throws Exception {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
}
