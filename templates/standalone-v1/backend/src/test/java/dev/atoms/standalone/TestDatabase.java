package dev.atoms.standalone;

import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Test-classpath only: verifies the dedicated database before Spring can migrate or write. */
public final class TestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final String IDENTITY_SQL = """
            SELECT current_database(), current_user, shobj_description(d.oid, 'pg_database'),
              (current_user = session_user AND r.rolcanlogin
                AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member = r.oid OR roleid = r.oid)
                AND shobj_description(r.oid, 'pg_authid') = ?) AS safe_role,
              (d.datdba <> r.oid AND has_database_privilege(r.oid, d.oid, 'CONNECT')
                AND NOT has_database_privilege(r.oid, d.oid, 'CREATE')
                AND NOT has_database_privilege(r.oid, d.oid, 'TEMP')) AS safe_database,
              (s.nspowner = r.oid AND has_schema_privilege(r.oid, s.oid, 'USAGE')
                AND has_schema_privilege(r.oid, s.oid, 'CREATE')
                AND NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'atoms')
                AND NOT EXISTS (SELECT FROM pg_namespace n
                  WHERE n.nspname <> 'standalone_app' AND n.nspname <> 'information_schema'
                    AND n.nspname !~ '^pg_'
                    AND (has_schema_privilege(r.oid, n.oid, 'USAGE')
                      OR has_schema_privilege(r.oid, n.oid, 'CREATE')))) AS safe_schema,
              current_setting('transaction_read_only') = 'on'
            FROM pg_database d JOIN pg_roles r ON r.rolname = current_user
            LEFT JOIN pg_namespace s ON s.nspname = 'standalone_app'
            WHERE d.datname = current_database()
            """;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        initialize(context, System.getenv("STANDALONE_TEST_CONFIG"));
    }

    static void initialize(ConfigurableApplicationContext context, String path) {
        boolean randomPort = "0".equals(context.getEnvironment().getProperty("server.port"));
        var environment = load(path);
        if (randomPort) environment.getPropertySources().addFirst(
                new MapPropertySource("testRandomPort", Map.of("server.port", 0)));
        context.setEnvironment(environment);
    }

    static ConfigurableEnvironment load() {
        return load(System.getenv("STANDALONE_TEST_CONFIG"));
    }

    static ConfigurableEnvironment load(String path) {
        var environment = configuration(path);
        verify(environment);
        // The replacement environment needs the fixed defaults before context refresh.
        // https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/context/config/ConfigDataEnvironmentPostProcessor.html
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    static ConfigurableEnvironment configuration(String value) {
        try {
            if (value == null || value.isBlank() || value.contains(",") || value.contains(";")) throw configError();
            var path = Path.of(value);
            if (!path.isAbsolute() || !value.endsWith(".properties")) throw configError();
            var properties = StartupConfig.read(path);
            if (!"true".equals(properties.getProperty("standalone.test-database"))) throw configError();
            // Reuse runtime validation, never add a production 'allow test database' switch.
            // This in-memory copy and the test marker conversion exist only in src/test.
            var runtimeCopy = new Properties();
            runtimeCopy.putAll(properties);
            runtimeCopy.setProperty("standalone.test-database", "false");
            var environment = StartupConfig.runtimeEnvironment(runtimeCopy);
            environment.getPropertySources().addFirst(new MapPropertySource("testConfig", Map.of(
                    "standalone.test-database", true,
                    "spring.config.location", "classpath:/application.yml,classpath:/application-test.yml")));
            return environment;
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            throw configError();
        }
    }

    static void verify(ConfigurableEnvironment environment) {
        if (!"true".equals(environment.getProperty("standalone.test-database"))) throw configError();
        var connectionProperties = new Properties();
        connectionProperties.setProperty("user", environment.getRequiredProperty("spring.datasource.username"));
        connectionProperties.setProperty("password", environment.getRequiredProperty("spring.datasource.password"));
        connectionProperties.setProperty("connectTimeout", "3");
        connectionProperties.setProperty("socketTimeout", "5");
        connectionProperties.setProperty("currentSchema", "pg_catalog");
        connectionProperties.setProperty("readOnly", "true");
        connectionProperties.setProperty("readOnlyMode", "always");
        connectionProperties.setProperty("logServerErrorDetail", "false");
        // pgJDBC enforces READ ONLY; assert the server's value rather than trusting the hint.
        // https://jdbc.postgresql.org/documentation/use/#connection-parameters
        try (var connection = DriverManager.getConnection(
                environment.getRequiredProperty("spring.datasource.url"), connectionProperties)) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(IDENTITY_SQL)) {
                statement.setQueryTimeout(3);
                statement.setString(1, marker(environment));
                try (var row = statement.executeQuery()) {
                    if (!row.next()) throw unsafe();
                    assertIdentity(environment, new Identity(row.getString(1), row.getString(2), row.getString(3),
                            row.getBoolean(4), row.getBoolean(5), row.getBoolean(6), row.getBoolean(7)));
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            throw new IllegalStateException("TEST_DATABASE_UNAVAILABLE: cannot verify the dedicated test connection.");
        }
    }

    static void assertIdentity(ConfigurableEnvironment environment, Identity identity) {
        String database = URI.create(environment.getRequiredProperty("spring.datasource.url").substring(5))
                .getPath().substring(1);
        if (!database.equals(identity.database())
                || !environment.getRequiredProperty("spring.datasource.username").equals(identity.role())
                || !marker(environment).equals(identity.marker()) || !identity.safeRole()
                || !identity.safeDatabase() || !identity.safeSchema() || !identity.readOnly()) throw unsafe();
    }

    private static String marker(ConfigurableEnvironment environment) {
        return "atoms-standalone:test:" + environment.getRequiredProperty("standalone.instance-id");
    }

    private static IllegalStateException configError() {
        return new IllegalStateException("TEST_DATABASE_CONFIG: STANDALONE_TEST_CONFIG must identify an explicit dedicated test configuration.");
    }

    private static IllegalStateException unsafe() {
        return new IllegalStateException("TEST_DATABASE_UNSAFE: database identity, privileges or dedicated schema do not match.");
    }

    record Identity(String database, String role, String marker, boolean safeRole,
                    boolean safeDatabase, boolean safeSchema, boolean readOnly) {}
}
