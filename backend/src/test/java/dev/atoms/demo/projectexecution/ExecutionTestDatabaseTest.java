package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionTestDatabaseTest {
    @TempDir Path temporary;
    static final String PASSWORD = "test-only-not-a-real-password";
    static final UUID PROJECT = UUID.fromString("1b509d48-ce13-42d6-8873-a3a80d051018");
    static final UUID BINDING = UUID.fromString("22163841-f1a3-4ae9-a2fc-2aa6aa52b067");

    @Test void readsOnlyExactPrivateConfigurationWithoutResolvingEnvironment() throws Exception {
        Path file = config(Map.of());
        var config = ExecutionPrivateConfig.read(file, List.of());
        assertThat(config.database()).isEqualTo("execution_a_test");
        assertThat(config.role()).isEqualTo("execution_a_role");
        assertThat(config.bindingId()).isEqualTo(BINDING);
        assertThat(config.toString()).doesNotContain(PASSWORD, file.toString());
        config.verify();
        assertThat(Files.readString(file)).contains(PASSWORD);
    }

    @Test void rejectsRuntimePlatformSystemTargetsAndConnectionParameterInjection() throws Exception {
        for (var change : List.of(
                Map.of("purpose", "runtime"), Map.of("port", "18181"), Map.of("schema", "public"),
                Map.of("database", "atoms_demo"), Map.of("database", "postgres"),
                Map.of("user", "postgres"), Map.of("password", "${DEEPSEEK_API_KEY}"),
                Map.of("spring.config.import", "/private/env"), Map.of("bindingId", "1-1-1-1-1"),
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/execution_a_test?currentSchema=generated_app"),
                Map.of("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/execution_a_test?currentSchema=generated_app&options=bad"),
                Map.of("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/another_database?currentSchema=generated_app"))) {
            Path file = config(change);
            assertThatThrownBy(() -> ExecutionPrivateConfig.read(file, List.of()))
                    .hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause().hasMessageNotContaining(PASSWORD);
        }
    }

    @Test void refusesDuplicateKeysLargeFilesAndProtectedProjectRoots() throws Exception {
        Path duplicate = config(Map.of());
        Files.writeString(duplicate, "\nuser=other_role\n", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> ExecutionPrivateConfig.read(duplicate, List.of())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        Path large = config(Map.of());
        Files.writeString(large, "#".repeat(8193));
        assertThatThrownBy(() -> ExecutionPrivateConfig.read(large, List.of())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        Path protectedFile = config(Map.of());
        assertThatThrownBy(() -> ExecutionPrivateConfig.read(protectedFile, List.of(protectedFile.getParent())))
                .hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
    }

    @Test void detectsPrivateFileAndParentTamperingWithoutRepairingEvidence() throws Exception {
        for (String change : List.of("mode", "parent-mode", "content", "replace", "symlink", "hardlink", "parent-replace")) {
            Path file = config(Map.of());
            var config = ExecutionPrivateConfig.read(file, List.of());
            switch (change) {
                case "mode" -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
                case "parent-mode" -> Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
                case "content" -> Files.writeString(file, Files.readString(file).replace(PASSWORD, "changed-but-still-private"));
                case "replace" -> {
                    Files.move(file, file.resolveSibling("previous"));
                    Files.copy(file.resolveSibling("previous"), file);
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                }
                case "symlink" -> {
                    Files.move(file, file.resolveSibling("previous"));
                    Files.createSymbolicLink(file, file.resolveSibling("previous"));
                }
                case "hardlink" -> Files.createLink(file.resolveSibling("alias"), file);
                case "parent-replace" -> {
                    Path saved = file.getParent().resolveSibling("saved-" + UUID.randomUUID());
                    Files.move(file.getParent(), saved);
                    Files.createDirectory(file.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    Files.move(saved.resolve(file.getFileName()), file);
                }
            }
            assertThatThrownBy(config::verify).as(change).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause();
            assertThat(Files.exists(file, LinkOption.NOFOLLOW_LINKS)).isTrue();
        }
    }

    @Test void registryRejectsCrossProjectReuseAndNondefaultPlatformTargetsBeforeConnecting() throws Exception {
        Path first = config(Map.of());
        assertThatThrownBy(() -> registry(Map.of(PROJECT, first, UUID.randomUUID(), first)))
                .hasMessage("EXECUTION_DB_BINDING_CONFLICT");
        Path roleReuse = config(Map.of("database", "other_test",
                "jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/other_test?currentSchema=generated_app",
                "bindingId", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> registry(Map.of(PROJECT, first, UUID.randomUUID(), roleReuse)))
                .hasMessage("EXECUTION_DB_BINDING_CONFLICT");
        assertThatThrownBy(() -> ExecutionTestDatabase.load(Map.of(PROJECT, first), List.of(),
                Set.of("execution_a_test"), Set.of("custom_platform_role")))
                .hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
        var registered = registry(Map.of(PROJECT, first));
        assertThatThrownBy(() -> registered.verify(UUID.randomUUID(), () -> Duration.ofSeconds(5)))
                .hasMessage("EXECUTION_DB_BINDING_NOT_FOUND");
        assertThatThrownBy(() -> registered.verify(PROJECT, () -> Duration.ZERO)).hasMessage("EXECUTION_TIMEOUT");
    }

    @Test void inspectionCredentialsCannotInheritConnectionOptionsOrEscapeThroughErrors() throws Exception {
        var config = ExecutionPrivateConfig.read(config(Map.of()), List.of());
        var properties = config.inspectionProperties(2);
        assertThat(properties.getProperty("connectTimeout")).isEqualTo("2");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("2");
        assertThat(properties.getProperty("readOnly")).isEqualTo("true");
        assertThat(properties.getProperty("currentSchema")).isEqualTo("pg_catalog");
        assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrder("user", "password", "connectTimeout",
                "socketTimeout", "cancelSignalTimeout", "readOnly", "readOnlyMode", "currentSchema", "sslmode",
                "gssEncMode", "jaasLogin", "ApplicationName");
        properties.setProperty("password", "overwritten-copy");
        assertThat(config.inspectionProperties(2).getProperty("password")).isEqualTo(PASSWORD);
        assertThatThrownBy(() -> config.inspectionProperties(0)).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause();
    }

    @Test void catalogPolicyRejectsEveryMissingPermissionAndIdentityCheck() throws Exception {
        var config = ExecutionPrivateConfig.read(config(Map.of()), List.of());
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.PreparedStatement.class);
        var rows = mock(java.sql.ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString("database_name")).thenReturn(config.database());
        when(rows.getString("role_name")).thenReturn(config.role());
        when(rows.getString("session_name")).thenReturn(config.role());
        when(rows.getString("database_marker")).thenReturn(config.marker());
        when(rows.getString("role_marker")).thenReturn(config.marker());
        var flags = List.of("readonly", "writable_target", "limited_role", "limited_database", "owns_schema", "no_other_schema");
        for (String field : flags) when(rows.getBoolean(field)).thenReturn(true);
        assertThat(ExecutionTestDatabase.inspect(connection, PROJECT, config, 2).bindingId()).isEqualTo(BINDING);
        for (String field : flags) {
            when(rows.getBoolean(field)).thenReturn(false);
            assertThatThrownBy(() -> ExecutionTestDatabase.inspect(connection, PROJECT, config, 2))
                    .as(field).hasMessage("EXECUTION_DB_TARGET_REJECTED").hasNoCause();
            when(rows.getBoolean(field)).thenReturn(true);
        }
        for (String field : List.of("database_name", "role_name", "session_name", "database_marker", "role_marker")) {
            String original = rows.getString(field);
            when(rows.getString(field)).thenReturn("wrong-identity-canary");
            assertThatThrownBy(() -> ExecutionTestDatabase.inspect(connection, PROJECT, config, 2))
                    .as(field).hasMessage("EXECUTION_DB_TARGET_REJECTED").hasNoCause();
            when(rows.getString(field)).thenReturn(original);
        }
    }

    static ExecutionTestDatabase registry(Map<UUID, Path> files) throws IOException {
        return ExecutionTestDatabase.load(files, List.of(), Set.of("custom_platform_db"), Set.of("custom_platform_role"));
    }

    Path config(Map<String, String> changes) throws Exception {
        Path directory = Files.createDirectory(temporary.toRealPath().resolve(UUID.randomUUID().toString()),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path file = Files.createFile(directory.resolve("test.properties"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        var properties = new Properties();
        properties.setProperty("database", "execution_a_test");
        properties.setProperty("user", "execution_a_role");
        properties.setProperty("password", PASSWORD);
        properties.setProperty("purpose", "test");
        properties.setProperty("bindingId", BINDING.toString());
        properties.setProperty("schema", "generated_app");
        properties.setProperty("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/execution_a_test?currentSchema=generated_app");
        properties.putAll(changes);
        try (var writer = Files.newBufferedWriter(file)) { properties.store(writer, "unit-test fixture only"); }
        return file;
    }
}
