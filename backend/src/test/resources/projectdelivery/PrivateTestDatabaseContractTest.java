package dev.atoms.generated;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Copied only to the controlled acceptance project; never shipped as application source. */
class PrivateTestDatabaseContractTest {
    @TempDir Path temporary;

    @Test void bothApprovedTestDatabasesPassRealIdentityChecksAndRemainEmpty() throws Exception {
        for (String label : List.of("a", "b")) {
            Path file = configuration(label, "test");
            assertEmpty(file);
            try (var context = context(file)) {
                new PrivateTestDatabase().initialize(context);
                assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
                assertThat(context.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("never");
            }
            assertEmpty(file);
        }
    }

    @Test void runtimeDatabaseCannotBecomeTestTargetByChangingThePrivateFilePurpose() throws Exception {
        for (String label : List.of("a", "b")) {
            var values = read(configuration(label, "app"));
            values.setProperty("purpose", "test"); values.remove("port");
            Path forged = Files.createTempFile(temporary.toRealPath(), "forged-", ".properties");
            Files.setPosixFilePermissions(forged, PosixFilePermissions.fromString("rw-------"));
            try (var output = Files.newOutputStream(forged)) { values.store(output, "private negative probe"); }
            try (var context = context(forged)) {
                assertThatThrownBy(() -> new PrivateTestDatabase().initialize(context))
                        .hasMessage("Explicit private test database configuration required").hasNoCause().hasNoSuppressedExceptions();
            }
        }
    }

    private GenericApplicationContext context(Path file) {
        var context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("explicit-test-file",
                Map.of("full-project.test-config", file.toString())));
        return context;
    }
    private Path configuration(String label, String purpose) {
        String directory = System.getProperty("delivery.test-config-directory");
        assertThat(directory).as("Explicit existing A/B acceptance configurations required").isNotBlank();
        return Path.of(directory).resolve("atoms_exec_" + label + "_" + purpose + "_20261020.properties");
    }
    private Properties read(Path file) throws Exception {
        var values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        return values;
    }
    private void assertEmpty(Path file) throws Exception {
        var values = read(file);
        var properties = new Properties();
        properties.setProperty("user", values.getProperty("user")); properties.setProperty("password", values.getProperty("password"));
        properties.setProperty("connectTimeout", "3"); properties.setProperty("socketTimeout", "5");
        try (var connection = DriverManager.getConnection(values.getProperty("jdbcUrl"), properties)) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery("SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='generated_app'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
            } finally { connection.rollback(); }
        }
    }
}
