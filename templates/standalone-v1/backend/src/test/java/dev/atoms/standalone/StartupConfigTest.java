package dev.atoms.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupConfigTest {
    private static final String LOCATION = "--spring.config.location=classpath:/application.yml,file:";
    private static final String ID = "82fe0a0b-19d9-45be-a6d3-578635e5be9e";
    private static final String PASSWORD = "fixture-only-private-canary-!$-\u00e9";
    @TempDir Path directory;

    @Test
    void localHttpUsesStableInstanceCookieAndOnlyTheExplicitDatabase() throws Exception {
        var args = config(settings());
        var first = StartupConfig.load(args);
        var second = StartupConfig.load(args);
        assertThat(first.getProperty("server.servlet.session.cookie.name")).isEqualTo("STANDALONE_" + ID);
        assertThat(second.getProperty("server.servlet.session.cookie.name"))
                .isEqualTo(first.getProperty("server.servlet.session.cookie.name"));
        assertThat(first.getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
        assertThat(first.getProperty("standalone.instance-id")).isEqualTo(ID);
        assertThat(first.getProperty("standalone.origin")).isEqualTo("http://127.0.0.1:8088");
        assertThat(first.getProperty("spring.datasource.password")).isEqualTo(PASSWORD);
    }

    @Test
    void aDifferentInstallationUsesADifferentCookie() throws Exception {
        var values = settings();
        values.put("APP_INSTANCE_ID", "b4019cee-c073-40d2-a347-9bdfe08a591a");
        var environment = StartupConfig.load(config(values));
        assertThat(environment.getProperty("server.servlet.session.cookie.name"))
                .isEqualTo("STANDALONE_b4019cee-c073-40d2-a347-9bdfe08a591a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "0.0.0.0", "::"})
    void explicitHttpsEnablesSecureCookiesIncludingBehindALocalProxy(String address) throws Exception {
        var values = settings();
        values.put("server.address", address);
        values.put("standalone.origin", "https://app.example.com/");
        var environment = StartupConfig.load(config(values));
        assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(environment.getProperty("standalone.origin")).isEqualTo("https://app.example.com");
    }

    @Test
    void ipv6LoopbackHttpIsSupportedWithoutDnsLookup() throws Exception {
        var values = settings();
        values.put("server.address", "::1");
        values.put("standalone.origin", "http://[::1]:8088");
        assertThat(StartupConfig.load(config(values)).getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("false");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void acceptsListenerPortBoundaries(int port) throws Exception {
        var values = settings();
        values.put("server.port", Integer.toString(port));
        values.put("standalone.origin", "http://127.0.0.1:" + port);
        assertThat(StartupConfig.load(config(values)).getProperty("server.port", Integer.class)).isEqualTo(port);
    }

    @Test
    void acceptsExactlyTheFileSizeLimitAndAPathContainingSpaces() throws Exception {
        config(settings());
        Path file = directory.resolve("application.properties");
        byte[] bytes = Files.readAllBytes(file);
        Files.writeString(file, "#".repeat(16 * 1024 - bytes.length), java.nio.file.StandardOpenOption.APPEND);
        Path spaced = directory.resolve("private config.properties");
        Files.move(file, spaced);
        assertThat(StartupConfig.load(LOCATION + spaced).getProperty("standalone.instance-id")).isEqualTo(ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDALONE_DB_URL", "STANDALONE_DB_USER", "STANDALONE_DB_PASSWORD",
            "APP_INSTANCE_ID", "server.address", "server.port", "standalone.origin"})
    void missingRequiredValuesDoNotFallBackToTheProcessEnvironment(String key) throws Exception {
        var values = settings();
        values.remove(key);
        assertRejected(config(values), "CONFIG_INVALID:");
    }

    @ParameterizedTest
    @MethodSource("invalidSettings")
    void refusesUnsafeOrPlaceholderValuesWithoutDisclosingThem(String key, String value) throws Exception {
        var values = settings();
        values.put(key, value);
        assertRejected(config(values), "CONFIG_INVALID:");
    }

    static Stream<Arguments> invalidSettings() {
        return Stream.of(
                Arguments.of("STANDALONE_DB_URL", "REPLACE_WITH_DATABASE_URL"),
                Arguments.of("STANDALONE_DB_URL", "jdbc:mysql://127.0.0.1/app"),
                Arguments.of("STANDALONE_DB_URL", "jdbc:postgresql://user:private-canary@localhost/app"),
                Arguments.of("STANDALONE_DB_URL", "jdbc:postgresql://localhost/app?password=private-canary"),
                Arguments.of("STANDALONE_DB_URL", "jdbc:postgresql://localhost/"),
                Arguments.of("STANDALONE_DB_URL", "jdbc:postgresql://localhost:99999/app"),
                Arguments.of("STANDALONE_DB_URL", "jdbc:postgresql://localhost/app#private-canary"),
                Arguments.of("STANDALONE_DB_USER", "REPLACE_WITH_DATABASE_USER"),
                Arguments.of("STANDALONE_DB_USER", "postgres"),
                Arguments.of("STANDALONE_DB_PASSWORD", "REPLACE_WITH_DATABASE_PASSWORD"),
                Arguments.of("STANDALONE_DB_PASSWORD", "${PRIVATE_CANARY}"),
                Arguments.of("STANDALONE_DB_PASSWORD", "changeme"),
                Arguments.of("STANDALONE_DB_PASSWORD", "password"),
                Arguments.of("STANDALONE_DB_PASSWORD", " "),
                Arguments.of("APP_INSTANCE_ID", "GENERATE_INSTANCE_ID"),
                Arguments.of("APP_INSTANCE_ID", "0-0-0-0-0"),
                Arguments.of("APP_INSTANCE_ID", "00000000-0000-0000-0000-000000000000"),
                Arguments.of("server.port", "0"), Arguments.of("server.port", "65536"),
                Arguments.of("server.port", "8088.0"),
                Arguments.of("server.address", "private-canary.example"),
                Arguments.of("server.address", "0.0.0.0"),
                Arguments.of("standalone.origin", "http://app.example.com:8088"),
                Arguments.of("standalone.origin", "http://127.0.0.1:8089"),
                Arguments.of("standalone.origin", "http://[::1]:8088"),
                Arguments.of("standalone.origin", "http://localhost.evil.example:8088"),
                Arguments.of("standalone.origin", "https://user:private-canary@app.example.com"),
                Arguments.of("standalone.origin", "https://app.example.com/path"),
                Arguments.of("standalone.origin", "https://app.example.com?private-canary"),
                Arguments.of("standalone.origin", "https://app.example.com#private-canary"),
                Arguments.of("standalone.origin", "https://app.example.com:0"),
                Arguments.of("standalone.origin", "https://app.example.com:65536"),
                Arguments.of("standalone.origin", "//app.example.com"),
                Arguments.of("standalone.origin", "ftp://app.example.com"),
                Arguments.of("standalone.test-database", "true"),
                Arguments.of("standalone.test-database", "FALSE"),
                Arguments.of("spring.config.import", "file:/private-canary.properties"),
                Arguments.of("spring.datasource.url", "jdbc:postgresql://localhost/platform"),
                Arguments.of("server.servlet.session.cookie.secure", "false"));
    }

    @Test
    void rejectsMissingRelativeOptionalAdditionalAndDuplicateConfigLocations() throws Exception {
        var args = config(settings());
        assertRejected(new String[0], "CONFIG_USAGE:");
        assertRejected(new String[] {LOCATION + "relative.properties"}, "CONFIG_USAGE:");
        assertRejected(new String[] {"--spring.config.location=optional:file:/missing.properties"}, "CONFIG_USAGE:");
        assertRejected(new String[] {args[0], args[0]}, "CONFIG_USAGE:");
        assertRejected(new String[] {args[0], "--server.address=0.0.0.0"}, "CONFIG_USAGE:");
        assertRejected(new String[] {args[0] + ",file:/other.properties"}, "CONFIG_USAGE:");
        assertRejected(new String[] {LOCATION + directory.resolve("missing.properties")}, "CONFIG_READ_FAILED:");
    }

    @Test
    void rejectsLinksMalformedUnicodeAndOversizedConfigurationWithoutParserDetails() throws Exception {
        config(settings());
        Path link = directory.resolve("link.properties");
        Files.createSymbolicLink(link, directory.resolve("application.properties"));
        assertRejected(new String[] {LOCATION + link}, "CONFIG_READ_FAILED:");
        Path file = directory.resolve("application.properties");
        Files.writeString(file, "STANDALONE_DB_PASSWORD=\\uBADZ-private-canary");
        assertRejected(new String[] {LOCATION + file}, "CONFIG_READ_FAILED:");
        Files.writeString(file, "#".repeat(16 * 1024 + 1));
        assertRejected(new String[] {LOCATION + file}, "CONFIG_READ_FAILED:");
    }

    @Test
    void actualJavaEntryRejectsInvalidConfigBeforeSpringOrDatabaseInitialization() throws Exception {
        var values = settings();
        values.put("STANDALONE_DB_PASSWORD", "REPLACE_WITH_DATABASE_PASSWORD");
        String[] args = config(values);
        assertThatThrownBy(() -> StandaloneApplication.main(args))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("CONFIG_INVALID:").hasNoCause();
    }

    @Test
    void realJavaProcessExitsNonzeroWithoutStartingSpringOrPrintingSecrets() throws Exception {
        var values = settings();
        values.remove("STANDALONE_DB_PASSWORD");
        Path log = directory.resolve("startup.log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                StandaloneApplication.class.getName(), config(values)[0]);
        builder.environment().put("STANDALONE_DB_PASSWORD", "environment-secret-canary");
        builder.environment().put("SPRING_APPLICATION_JSON", "{\"STANDALONE_DB_PASSWORD\":\"json-secret-canary\"}");
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(Files.readString(log)).contains("CONFIG_INVALID:")
                    .doesNotContain("environment-secret-canary", "json-secret-canary", "Spring Boot", "HikariPool");
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void springBindsValidatedValuesAndFixedDefaultsWithoutInheritingSystemProperties() throws Exception {
        String key = "spring.datasource.url";
        String previous = System.getProperty(key);
        System.setProperty(key, "jdbc:postgresql://localhost/platform-private-canary");
        try {
            ConfigurableEnvironment environment = StartupConfig.load(config(settings()));
            var application = new SpringApplication(ConfigProbe.class);
            application.setEnvironment(environment);
            application.setAddCommandLineProperties(false);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
            // No auto-configuration or database beans: verify real ConfigData + property binding only.
            try (var context = application.run()) {
                var binder = Binder.get(context.getEnvironment());
                var database = binder.bind("spring.datasource", DataSourceProperties.class)
                        .orElseThrow(() -> new AssertionError("Missing datasource configuration"));
                assertThat(database.getUrl()).isEqualTo(settings().get("STANDALONE_DB_URL"));
                assertThat(database.getPassword()).isEqualTo(PASSWORD);
                var server = binder.bind("server", ServerProperties.class)
                        .orElseThrow(() -> new AssertionError("Missing server configuration"));
                assertThat(server.getPort()).isEqualTo(8088);
                assertThat(server.getServlet().getSession().getCookie().getName()).isEqualTo("STANDALONE_" + ID);
                assertThat(server.getServlet().getSession().getCookie().getSecure()).isFalse();
                assertThat(server.getServlet().getSession().getCookie().getHttpOnly()).isTrue();
                assertThat(server.getServlet().getSession().getCookie().getSameSite().attributeValue()).isEqualTo("Lax");
                assertThat(context.getEnvironment().getProperty("spring.flyway.create-schemas")).isEqualTo("false");
                assertThat(context.getEnvironment().getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
                assertThat(context.getEnvironment().getProperty("spring.config.location")).isEqualTo("classpath:/application.yml");
            }
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConfigProbe {}

    private Map<String, String> settings() {
        var values = new LinkedHashMap<String, String>();
        values.put("STANDALONE_DB_URL", "jdbc:postgresql://127.0.0.1:1/standalone_fixture");
        values.put("STANDALONE_DB_USER", "standalone_fixture");
        values.put("STANDALONE_DB_PASSWORD", PASSWORD);
        values.put("APP_INSTANCE_ID", ID);
        values.put("server.address", "127.0.0.1");
        values.put("server.port", "8088");
        values.put("standalone.origin", "http://127.0.0.1:8088");
        values.put("standalone.test-database", "false");
        return values;
    }

    private String[] config(Map<String, String> values) throws IOException {
        Path file = directory.resolve("application.properties");
        var properties = new Properties();
        properties.putAll(values);
        try (var output = Files.newOutputStream(file)) {
            properties.store(output, "Test fixture only: never connect to a database");
        }
        return new String[] {LOCATION + file};
    }

    private void assertRejected(String[] args, String code) {
        assertThatThrownBy(() -> StartupConfig.load(args)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(code).hasNoCause()
                .hasMessageNotContaining("private-canary").hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining(directory.toString());
    }
}
