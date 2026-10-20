package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in, existing approved resources only. No database DDL/DML or administrator credentials. */
@Tag("project-execution-contract")
class ExecutionTestDatabaseIT {
    private static final UUID A = UUID.fromString("f7a7917c-7b41-4fd6-b224-25aeeaa9e579");
    private static final UUID B = UUID.fromString("cdcd56cb-98a1-4ef4-aac9-180589576a91");
    private static final String A_TEST = "atoms_exec_a_test_20261020", B_TEST = "atoms_exec_b_test_20261020";
    private static final String A_APP = "atoms_exec_a_app_20261020", B_APP = "atoms_exec_b_app_20261020";
    @TempDir Path temporary;
    private Path configuration;

    @BeforeEach void explicitConfigurationIsRequired() throws Exception {
        String configured = System.getProperty("execution.test-config-directory");
        assertThat(configured != null && !configured.isBlank())
                .as("Explicit private execution.test-config-directory is required; missing environment is not a skip").isTrue();
        configuration = Path.of(configured);
        assertThat(configuration.isAbsolute() && configuration.normalize().equals(configuration)).isTrue();
        ExecutionRootLease.noLinks(configuration);
        assertThat(Files.getPosixFilePermissions(configuration)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test void twoActualTestDatabasesHaveDistinctIdentitiesAndRemainUnchanged() throws Exception {
        long firstBefore = relationCount(A_TEST), secondBefore = relationCount(B_TEST);
        var registered = registry(Map.of(A, path(A_TEST), B, path(B_TEST)));
        var first = registered.verify(A, deadline()); var second = registered.verify(B, deadline());
        assertThat(first.projectId()).isEqualTo(A); assertThat(second.projectId()).isEqualTo(B);
        assertThat(first.databaseOid()).isPositive().isNotEqualTo(second.databaseOid());
        assertThat(first.roleOid()).isPositive().isNotEqualTo(second.roleOid());
        assertThat(first.bindingId()).isNotEqualTo(second.bindingId());
        assertThat(first.relationCount()).isEqualTo(firstBefore); assertThat(second.relationCount()).isEqualTo(secondBefore);
        assertThat(registered.verify(A, deadline())).isEqualTo(first);
        assertThat(registered.verify(B, deadline())).isEqualTo(second);
    }

    @Test void falsifyingLocalPurposeCannotTurnEitherActualRuntimeDatabaseIntoATestDatabase() throws Exception {
        for (String database : List.of(A_APP, B_APP)) {
            long before = relationCount(database);
            assertThatThrownBy(() -> registry(Map.of(A, path(database)))).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID");
            Path forged = copy(database, Map.of("purpose", "test"), Set.of("port"));
            assertThatThrownBy(() -> registry(Map.of(A, forged)).verify(A, deadline()))
                    .hasMessage("EXECUTION_DB_TARGET_REJECTED").hasNoCause().hasNoSuppressedExceptions();
            assertThat(relationCount(database)).isEqualTo(before);
        }
    }

    @Test void mismatchingBindingAndWrongPasswordFailWithoutCredentialDisclosure() throws Exception {
        long before = relationCount(A_TEST);
        Path incorrectMarker = copy(A_TEST, Map.of("bindingId", UUID.randomUUID().toString()), Set.of());
        assertThatThrownBy(() -> registry(Map.of(A, incorrectMarker)).verify(A, deadline()))
                .hasMessage("EXECUTION_DB_TARGET_REJECTED").hasNoCause().hasNoSuppressedExceptions();
        Path incorrectPassword = copy(A_TEST, Map.of("password", "intentionally-wrong-execution-canary"), Set.of());
        assertThatThrownBy(() -> registry(Map.of(A, incorrectPassword)).verify(A, deadline()))
                .hasMessage("EXECUTION_DB_UNAVAILABLE").hasNoCause().hasNoSuppressedExceptions();
        assertThat(relationCount(A_TEST)).isEqualTo(before);
    }

    @Test void registeredFileReplacementAndCrossProjectReuseAreRejectedBeforeQuery() throws Exception {
        long firstBefore = relationCount(A_TEST), secondBefore = relationCount(B_TEST);
        Path first = copy(A_TEST, Map.of(), Set.of());
        var registry = registry(Map.of(A, first));
        assertThat(registry.verify(A, deadline()).relationCount()).isEqualTo(firstBefore);
        Files.move(first, first.resolveSibling("original.properties"));
        Files.copy(first.resolveSibling("original.properties"), first);
        Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> registry.verify(A, deadline())).hasMessage("EXECUTION_DB_CONFIGURATION_INVALID").hasNoCause();
        assertThatThrownBy(() -> registry(Map.of(A, path(A_TEST), B, path(A_TEST)))).hasMessage("EXECUTION_DB_BINDING_CONFLICT");
        assertThat(relationCount(A_TEST)).isEqualTo(firstBefore); assertThat(relationCount(B_TEST)).isEqualTo(secondBefore);
    }

    private ExecutionTestDatabase registry(Map<UUID, Path> bindings) throws Exception {
        return ExecutionTestDatabase.load(bindings, List.of(temporary.toRealPath().resolve("project")),
                Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
    }

    private ExecutionProcess.Deadline deadline() { return new ExecutionProcess.Deadline(Duration.ofSeconds(15)); }
    private Path path(String name) { return configuration.resolve(name + ".properties"); }

    private Path copy(String name, Map<String, String> changes, Set<String> removed) throws Exception {
        var properties = read(name); properties.putAll(changes); removed.forEach(properties::remove);
        Path directory = Files.createDirectory(temporary.toRealPath().resolve(UUID.randomUUID().toString()),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path copy = Files.createFile(directory.resolve("fixture.properties"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var writer = Files.newBufferedWriter(copy)) { properties.store(writer, "Private short-lived negative fixture"); }
        return copy;
    }

    private Properties read(String name) throws Exception {
        Path file = path(name);
        ExecutionRootLease.noLinks(file);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.size(file) <= 8192).isTrue();
        var properties = new Properties();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { properties.load(input); }
        return properties;
    }

    private long relationCount(String name) throws Exception {
        // Verify negative checks did not mutate either approved test or runtime schema.
        var source = read(name); var properties = new Properties();
        properties.setProperty("user", source.getProperty("user")); properties.setProperty("password", source.getProperty("password"));
        properties.setProperty("connectTimeout", "3"); properties.setProperty("socketTimeout", "5");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/" + name, properties)) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery("""
                        SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname='generated_app'
                        """)) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
            } finally { connection.rollback(); }
        } catch (java.sql.SQLException error) { throw new AssertionError("Read-only emptiness check failed"); }
    }
}
