package dev.atoms.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDatabaseTest {
    private static final String ID = "82fe0a0b-19d9-45be-a6d3-578635e5be9e";
    private static final String MARKER = "atoms-standalone:test:" + ID;
    @TempDir Path directory;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"test.properties", "${STANDALONE_TEST_CONFIG}", "/not-present/test.properties"})
    void missingOrNonexplicitConfigurationFailsWithoutEnvironmentFallback(String path) {
        assertThatThrownBy(() -> TestDatabase.configuration(path)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("TEST_DATABASE_CONFIG:").hasNoCause();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "TRUE", "yes"})
    void runtimeConfigurationCannotBeUsedForTests(String marker) throws Exception {
        var values = settings();
        if (marker == null) values.remove("standalone.test-database");
        else values.setProperty("standalone.test-database", marker);
        assertThatThrownBy(() -> TestDatabase.configuration(write(values)))
                .hasMessageStartingWith("TEST_DATABASE_CONFIG:").hasNoCause();
    }

    @Test
    void parsesOnlyMarkedTestConfigUsingTheSameCredentialAndOriginChecksAsRuntime() throws Exception {
        var environment = TestDatabase.configuration(write(settings()));
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:1/dedicated_test");
        assertThat(environment.getProperty("standalone.test-database")).isEqualTo("true");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("private-fixture-canary");
        assertThat(environment.getProperty("spring.config.location"))
                .isEqualTo("classpath:/application.yml,classpath:/application-test.yml");
        var values = settings();
        values.setProperty("STANDALONE_DB_PASSWORD", "REPLACE_WITH_DATABASE_PASSWORD");
        String placeholderFile = write(values);
        assertThatThrownBy(() -> TestDatabase.configuration(placeholderFile))
                .hasMessageStartingWith("TEST_DATABASE_CONFIG:").hasNoCause();
        values = settings();
        values.setProperty("spring.datasource.url", "jdbc:postgresql://localhost/platform-canary");
        String unknownKeyFile = write(values);
        assertThatThrownBy(() -> TestDatabase.configuration(unknownKeyFile))
                .hasMessageStartingWith("TEST_DATABASE_CONFIG:").hasNoCause();
    }

    @Test
    void doesNotTurnTheOriginalTestFileIntoAnAcceptedRuntimeConfiguration() throws Exception {
        String file = write(settings());
        byte[] before = Files.readAllBytes(Path.of(file));
        TestDatabase.configuration(file);
        assertThat(Files.readAllBytes(Path.of(file))).isEqualTo(before);
        assertThatThrownBy(() -> StandaloneApplication.main(new String[] {
                "--spring.config.location=classpath:/application.yml,file:" + file}))
                .hasMessageStartingWith("CONFIG_INVALID:").hasNoCause();
    }

    @Test
    void aVerifiedReadOnlySnapshotOfTheDedicatedDatabasePasses() throws Exception {
        TestDatabase.assertIdentity(TestDatabase.configuration(write(settings())), validIdentity());
    }

    @ParameterizedTest
    @MethodSource("unsafeIdentities")
    void actualIdentityOrPrivilegeMismatchIsRejected(TestDatabase.Identity identity) throws Exception {
        var environment = TestDatabase.configuration(write(settings()));
        assertThatThrownBy(() -> TestDatabase.assertIdentity(environment, identity))
                .hasMessageStartingWith("TEST_DATABASE_UNSAFE:").hasNoCause()
                .hasMessageNotContaining("private-fixture-canary");
    }

    static Stream<TestDatabase.Identity> unsafeIdentities() {
        return Stream.of(
                new TestDatabase.Identity("another_database", "dedicated_test", MARKER, true, true, true, true),
                new TestDatabase.Identity("dedicated_test", "another_role", MARKER, true, true, true, true),
                new TestDatabase.Identity("dedicated_test", "dedicated_test", null, true, true, true, true),
                new TestDatabase.Identity("dedicated_test", "dedicated_test", "atoms-standalone:runtime:" + ID, true, true, true, true),
                new TestDatabase.Identity("dedicated_test", "dedicated_test", "atoms-standalone:test:another-id", true, true, true, true),
                new TestDatabase.Identity("dedicated_test", "dedicated_test", MARKER, false, true, true, true),
                new TestDatabase.Identity("dedicated_test", "dedicated_test", MARKER, true, false, true, true),
                new TestDatabase.Identity("dedicated_test", "dedicated_test", MARKER, true, true, false, true),
                new TestDatabase.Identity("dedicated_test", "dedicated_test", MARKER, true, true, true, false));
    }

    @Test
    void initializerRejectsBadConfigBeforeAnyContextBeanIsCreated() {
        try (var context = new GenericApplicationContext()) {
            boolean[] created = {false};
            context.registerBean("mutationCanary", Object.class, () -> { created[0] = true; return new Object(); });
            assertThatThrownBy(() -> TestDatabase.initialize(context, null)).hasMessageStartingWith("TEST_DATABASE_CONFIG:");
            assertThat(created[0]).isFalse();
            assertThat(context.isActive()).isFalse();
        }
    }

    @Test
    void testOnlyConfigResourceDoesNotSupplyFallbackCredentialsOrEnableDatabaseCleaning() throws Exception {
        try (var resource = getClass().getResourceAsStream("/application-test.yml")) {
            assertThat(resource).isNotNull();
            String text = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text).contains("clean-disabled: true", "initialize-schema: never")
                    .doesNotContain("password:", "jdbc:postgresql:", ".env", "spring.config.import");
        }
    }

    private static TestDatabase.Identity validIdentity() {
        return new TestDatabase.Identity("dedicated_test", "dedicated_test", MARKER, true, true, true, true);
    }

    private Properties settings() {
        var values = new Properties();
        values.setProperty("STANDALONE_DB_URL", "jdbc:postgresql://127.0.0.1:1/dedicated_test");
        values.setProperty("STANDALONE_DB_USER", "dedicated_test");
        values.setProperty("STANDALONE_DB_PASSWORD", "private-fixture-canary");
        values.setProperty("APP_INSTANCE_ID", ID);
        values.setProperty("server.address", "127.0.0.1");
        values.setProperty("server.port", "18088");
        values.setProperty("standalone.origin", "http://127.0.0.1:18088");
        values.setProperty("standalone.test-database", "true");
        return values;
    }

    private String write(Properties values) throws Exception {
        var file = directory.resolve("test.properties");
        try (var output = Files.newOutputStream(file)) { values.store(output, "Test fixture only"); }
        return file.toString();
    }
}
