package dev.atoms.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL, explicit test credentials, no administrator access and no schema writes. */
class DedicatedDatabaseIT {
    @TempDir Path directory;

    @BeforeAll
    static void requireDedicatedDatabaseBeforeTests() {
        TestDatabase.load(); // Missing configuration is a failure, never a skipped integration suite.
    }

    @Test
    void realConnectionAuthenticatesAndRepeatedVerificationDoesNotCreateTables() throws Exception {
        var environment = TestDatabase.load();
        long before = relationCount(environment);
        TestDatabase.verify(environment);
        TestDatabase.verify(environment);
        assertThat(relationCount(environment)).isEqualTo(before);
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("3");
        assertThat(environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.flyway.default-schema")).isEqualTo("standalone_app");
    }

    @Test
    void forgedInstallationIdIsRejectedByActualDatabaseMarker() {
        var environment = TestDatabase.load();
        environment.getPropertySources().addFirst(new MapPropertySource("forgedIdentity", Map.of(
                "standalone.instance-id", UUID.randomUUID().toString())));
        assertThatThrownBy(() -> TestDatabase.verify(environment))
                .hasMessageStartingWith("TEST_DATABASE_UNSAFE:").hasNoCause();
    }

    @Test
    void unavailableDatabaseReportsASanitizedFailureWithoutFallback() {
        var environment = TestDatabase.load();
        environment.getPropertySources().addFirst(new MapPropertySource("unavailable", Map.of(
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/private_test_canary")));
        assertThatThrownBy(() -> TestDatabase.verify(environment))
                .hasMessageStartingWith("TEST_DATABASE_UNAVAILABLE:").hasNoCause()
                .hasMessageNotContaining("private_test_canary").hasMessageNotContaining("jdbc:");
    }

    @Test
    void initializerLoadsFixedConfigurationBeforeBeansAndPreservesRandomTestPort() {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("randomPort", Map.of("server.port", "0")));
            boolean[] created = {false};
            context.registerBean("mutationCanary", Object.class, () -> { created[0] = true; return new Object(); });
            new TestDatabase().initialize(context);
            assertThat(created[0]).isFalse();
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("0");
            assertThat(context.getEnvironment().getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
            context.refresh();
            assertThat(created[0]).isTrue();
        }
    }

    @Test
    void actualDatabaseMismatchPreventsContextRefreshOrMutationBeans() throws Exception {
        var values = StartupConfig.read(Path.of(System.getenv("STANDALONE_TEST_CONFIG")));
        values.setProperty("APP_INSTANCE_ID", UUID.randomUUID().toString());
        Path file = directory.resolve("wrong-identity.properties");
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(file)) { values.store(output, "Private integration fixture"); }
        try (var context = new GenericApplicationContext()) {
            boolean[] created = {false};
            context.registerBean("mutationCanary", Object.class, () -> { created[0] = true; return new Object(); });
            assertThatThrownBy(() -> TestDatabase.initialize(context, file.toString()))
                    .hasMessageStartingWith("TEST_DATABASE_UNSAFE:").hasNoCause();
            assertThat(created[0]).isFalse();
            assertThat(context.isActive()).isFalse();
        }
    }

    @Test
    void normalJavaEntryRejectsTheRealTestConfigurationBeforeStarting() {
        assertThatThrownBy(() -> StandaloneApplication.main(new String[] {
                "--spring.config.location=classpath:/application.yml,file:" + System.getenv("STANDALONE_TEST_CONFIG")}))
                .hasMessageStartingWith("CONFIG_INVALID:").hasNoCause();
    }

    private long relationCount(ConfigurableEnvironment environment) throws Exception {
        try (var connection = DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    SELECT count(*) FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'standalone_app'
                    """)) {
                statement.setQueryTimeout(3);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    return result.getLong(1);
                }
            } finally { connection.rollback(); }
        }
    }
}
