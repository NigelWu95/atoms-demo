package dev.atoms.standalone;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Guard runs before Flyway may create even its history table. No automatic adoption/reset. */
@Component
final class DatabaseInstallation implements FlywayMigrationStrategy {
    private static final Logger log = LoggerFactory.getLogger(DatabaseInstallation.class);
    private static final long LOCK = 1748392052L;
    private final UUID instanceId;
    private final String database;
    private final String role;
    private final boolean test;

    DatabaseInstallation(Environment environment) {
        try {
            String id = environment.getRequiredProperty("standalone.instance-id");
            instanceId = UUID.fromString(id);
            if (instanceId.version() != 4 || instanceId.variant() != 2 || !instanceId.toString().equals(id)) throw new IllegalArgumentException();
            database = URI.create(environment.getRequiredProperty("spring.datasource.url").substring(5)).getPath().substring(1);
            role = environment.getRequiredProperty("spring.datasource.username");
            // Only src/test's verified bootstrap supplies true; StartupConfig rejects it.
            test = environment.getProperty("standalone.test-database", Boolean.class, false);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            throw failure("INSTALLATION_CONFIG");
        }
    }

    @Override
    public void migrate(Flyway flyway) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try {
            migrateGuarded(flyway);
            log.atInfo().addKeyValue("event", "installation_ready").addKeyValue("entryPoint", "startup")
                    .addKeyValue("requestId", requestId).addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log("Dedicated installation verified");
        } catch (IllegalStateException error) {
            log.atError().addKeyValue("event", "installation_rejected").addKeyValue("entryPoint", "startup")
                    .addKeyValue("requestId", requestId).addKeyValue("code", error.getMessage().split(":", 2)[0])
                    .log("Dedicated installation could not start");
            throw error;
        }
    }

    private void migrateGuarded(Flyway flyway) {
        var config = flyway.getConfiguration();
        if (!Arrays.equals(config.getSchemas(), new String[] {"standalone_app"})
                || !"standalone_app".equals(config.getDefaultSchema()) || config.isCreateSchemas()
                || !config.isCleanDisabled() || config.isBaselineOnMigrate()
                || !instanceId.toString().equals(config.getPlaceholders().get("instanceId"))) throw failure("INSTALLATION_CONFIG");
        // Session lock spans Flyway's own transactions/connections. Always unlock before
        // returning a pooled connection. No JVM-only lock or check-then-insert race.
        // https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
        // https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html
        try (var connection = config.getDataSource().getConnection(); var lock = connection.createStatement()) {
            connection.setReadOnly(true);
            lock.setQueryTimeout(10);
            try {
                DatabaseTarget.inspect(connection).require(database, role, test);
                lock.execute("SELECT pg_catalog.pg_advisory_lock(" + LOCK + ")");
                DatabaseTarget.inspect(connection).require(database, role, test);
                checkInstallation(connection, true);
                flyway.migrate();
                checkInstallation(connection, false);
            } finally {
                lock.execute("SELECT pg_catalog.pg_advisory_unlock(" + LOCK + ")");
                connection.setReadOnly(false);
            }
        } catch (SQLException ignored) {
            throw failure("DATABASE_UNAVAILABLE");
        } catch (FlywayException ignored) {
            throw failure("INSTALLATION_MIGRATION_FAILED");
        }
    }

    void checkInstallation(Connection connection, boolean allowEmpty) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT c.relkind = 'r' AND c.relowner = r.oid AND NOT c.relrowsecurity
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_roles r ON r.rolname = current_user
                WHERE n.nspname = 'standalone_app' AND c.relname = 'installation'
                """)) {
            statement.setQueryTimeout(3);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    if (!allowEmpty || !emptySchema(connection)) throw failure("INSTALLATION_UNCLAIMED");
                    return;
                }
                if (!row.getBoolean(1)) throw failure("INSTALLATION_UNSAFE");
            }
        }
        try (var statement = connection.prepareStatement("SELECT singleton, instance_id, format_version FROM standalone_app.installation LIMIT 2")) {
            statement.setQueryTimeout(3);
            try (var row = statement.executeQuery()) {
                if (!row.next() || !row.getBoolean(1) || !instanceId.equals(row.getObject(2, UUID.class))
                        || row.getInt(3) != 1 || row.next()) throw failure("INSTALLATION_MISMATCH");
            }
        }
    }

    private boolean emptySchema(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT NOT EXISTS (SELECT FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'standalone_app')
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                  WHERE n.nspname = 'standalone_app')
                AND NOT EXISTS (SELECT FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                  WHERE n.nspname = 'standalone_app')
                """)) {
            statement.setQueryTimeout(3);
            try (var row = statement.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code + ": dedicated installation verification failed; no automatic reset was attempted.");
    }
}
