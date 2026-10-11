package dev.atoms.standalone;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseInstallationIT {
    private ConfigurableEnvironment environment;
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareOnlyTheVerifiedDedicatedTestDatabase() {
        environment = TestDatabase.load();
        TestDatabase.resetInstallation();
        jdbc = new JdbcTemplate(TestDatabase.dataSource(environment));
    }

    @Test
    void firstMigrationClaimsExactlyOneIdentityAndRestartPreservesVisitorAndSession() {
        migrate(environment);
        assertThat(jdbc.queryForObject("SELECT instance_id::text FROM standalone_app.installation", String.class))
                .isEqualTo(environment.getProperty("standalone.instance-id"));
        UUID visitor = UUID.randomUUID();
        jdbc.update("INSERT INTO standalone_app.guest_users(id) VALUES (?)", visitor);
        String session = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO standalone_app.spring_session
                (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)
                VALUES (?, ?, 1, 1, 604800, 9999999999999)
                """, session, session);
        migrate(environment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.guest_users WHERE id = ?", Integer.class, visitor)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.spring_session WHERE session_id = ?", Integer.class, session)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.installation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
    }

    @Test
    void mismatchedInstanceFailsBeforeMigrationAndDoesNotOverwriteTheClaim() {
        migrate(environment);
        var other = withId(UUID.randomUUID());
        var enteredFlyway = new AtomicBoolean();
        var callback = new BaseCallback() {
            @Override public boolean supports(Event event, Context context) { return event == Event.BEFORE_MIGRATE; }
            @Override public void handle(Event event, Context context) { enteredFlyway.set(true); }
        };
        var rejected = Flyway.configure().configuration(flyway(other).getConfiguration()).callbacks(callback).load();
        assertThatThrownBy(() -> new DatabaseInstallation(other).migrate(rejected))
                .hasMessageStartingWith("INSTALLATION_MISMATCH:").hasNoCause();
        assertThat(enteredFlyway).isFalse();
        assertThat(jdbc.queryForObject("SELECT instance_id::text FROM standalone_app.installation", String.class))
                .isEqualTo(environment.getProperty("standalone.instance-id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.flyway_schema_history", Integer.class)).isEqualTo(1);
        var accepted = Flyway.configure().configuration(flyway(environment).getConfiguration()).callbacks(callback).load();
        new DatabaseInstallation(environment).migrate(accepted);
        assertThat(enteredFlyway).isTrue(); // The probe is real, not a callback that never fires.
    }

    @Test
    void refusesToAdoptAnUnclaimedNonemptySchemaBeforeFlywayCreatesHistory() {
        jdbc.execute("CREATE TABLE standalone_app.unclaimed_canary (id integer)");
        try {
            jdbc.update("INSERT INTO standalone_app.unclaimed_canary VALUES (42)");
            assertThatThrownBy(() -> migrate(environment)).hasMessageStartingWith("INSTALLATION_UNCLAIMED:").hasNoCause();
            assertThat(jdbc.queryForObject("SELECT to_regclass('standalone_app.flyway_schema_history') IS NULL", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT id FROM standalone_app.unclaimed_canary", Integer.class)).isEqualTo(42);
        } finally { jdbc.execute("DROP TABLE standalone_app.unclaimed_canary"); }
    }

    @Test
    void testDatabaseCannotBeMigratedByNormalRuntimeEvenWithItsCredentials() {
        environment.getPropertySources().addFirst(new MapPropertySource("runtimePurpose", Map.of("standalone.test-database", false)));
        assertThatThrownBy(() -> migrate(environment)).hasMessageStartingWith("DATABASE_UNSAFE:").hasNoCause();
        assertThat(jdbc.queryForObject("SELECT to_regclass('standalone_app.flyway_schema_history') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void differentIdsRacingOnFirstStartupHaveExactlyOneWinner() throws Exception {
        var first = withId(UUID.randomUUID());
        var second = withId(UUID.randomUUID());
        try {
            var results = race(first, second);
            assertThat(results).containsExactlyInAnyOrder("READY", "INSTALLATION_MISMATCH");
            String winningId = (results.get(0).equals("READY") ? first : second).getProperty("standalone.instance-id");
            assertThat(jdbc.queryForObject("SELECT instance_id::text FROM standalone_app.installation", String.class)).isEqualTo(winningId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.flyway_schema_history", Integer.class)).isEqualTo(1);
        } finally {
            // Restore only the IDs introduced by this test, using its already verified source.
            jdbc.update("UPDATE standalone_app.installation SET instance_id = ? WHERE instance_id IN (?, ?)",
                    UUID.fromString(environment.getRequiredProperty("standalone.instance-id")),
                    UUID.fromString(first.getRequiredProperty("standalone.instance-id")),
                    UUID.fromString(second.getRequiredProperty("standalone.instance-id")));
        }
    }

    @Test
    void sameIdRacingOnFirstStartupBothSucceedWithOneMigration() throws Exception {
        assertThat(race(environment, TestDatabase.load())).containsExactly("READY", "READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.installation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.flyway_schema_history", Integer.class)).isEqualTo(1);
    }

    @Test
    void emptyInstallationRecordIsNotSilentlyReclaimed() {
        migrate(environment);
        jdbc.update("DELETE FROM standalone_app.installation");
        try {
            assertThatThrownBy(() -> migrate(environment)).hasMessageStartingWith("INSTALLATION_MISMATCH:").hasNoCause();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.installation", Integer.class)).isZero();
        } finally {
            jdbc.update("INSERT INTO standalone_app.installation(singleton, instance_id, format_version) VALUES (true, ?, 1)",
                    UUID.fromString(environment.getRequiredProperty("standalone.instance-id")));
        }
    }

    @Test
    void testBootstrapAndCleanupBothRejectAnExistingWrongInstallationIdentity() {
        migrate(environment);
        UUID other = UUID.randomUUID();
        jdbc.update("UPDATE standalone_app.installation SET instance_id = ?", other);
        try {
            assertThatThrownBy(TestDatabase::load).hasMessageStartingWith("TEST_DATABASE_UNSAFE:").hasNoCause();
            assertThatThrownBy(TestDatabase::resetInstallation).hasMessageStartingWith("TEST_DATABASE_UNSAFE:").hasNoCause();
            assertThat(jdbc.queryForObject("SELECT instance_id FROM standalone_app.installation", UUID.class)).isEqualTo(other);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM standalone_app.flyway_schema_history", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update("UPDATE standalone_app.installation SET instance_id = ? WHERE instance_id = ?",
                    UUID.fromString(environment.getRequiredProperty("standalone.instance-id")), other);
        }
    }

    @Test
    void actualDatabaseMismatchFailsBeforeCreatingHistoryWithoutConnectingToPlatform() {
        var verifiedSource = TestDatabase.dataSource(environment);
        environment.getPropertySources().addFirst(new MapPropertySource("wrongExpectedDatabase", Map.of(
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/platform_canary")));
        var flyway = Flyway.configure().dataSource(verifiedSource).schemas("standalone_app").defaultSchema("standalone_app")
                .createSchemas(false).cleanDisabled(true)
                .placeholders(Map.of("instanceId", environment.getRequiredProperty("standalone.instance-id"))).load();
        assertThatThrownBy(() -> new DatabaseInstallation(environment).migrate(flyway))
                .hasMessageStartingWith("DATABASE_UNSAFE:").hasNoCause().hasMessageNotContaining("platform_canary");
        assertThat(jdbc.queryForObject("SELECT to_regclass('standalone_app.flyway_schema_history') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void unavailableConnectionIsSanitizedAndDoesNotFallBack() {
        environment.getPropertySources().addFirst(new MapPropertySource("unavailable", Map.of(
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/private_canary")));
        assertThatThrownBy(() -> migrate(environment)).hasMessageStartingWith("DATABASE_UNAVAILABLE:")
                .hasNoCause().hasMessageNotContaining("private_canary").hasMessageNotContaining("jdbc:");
        assertThat(jdbc.queryForObject("SELECT to_regclass('standalone_app.flyway_schema_history') IS NULL", Boolean.class)).isTrue();
    }

    private List<String> race(ConfigurableEnvironment first, ConfigurableEnvironment second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(configuration -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Startup race did not begin");
                try { migrate(configuration); return "READY"; }
                catch (IllegalStateException error) { return error.getMessage().split(":", 2)[0]; }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private ConfigurableEnvironment withId(UUID id) {
        var other = TestDatabase.load();
        other.getPropertySources().addFirst(new MapPropertySource("competingInstallation", Map.of("standalone.instance-id", id.toString())));
        return other;
    }

    static void migrate(ConfigurableEnvironment configuration) {
        new DatabaseInstallation(configuration).migrate(flyway(configuration));
    }

    private static Flyway flyway(ConfigurableEnvironment configuration) {
        return Flyway.configure().dataSource(TestDatabase.dataSource(configuration))
                .schemas("standalone_app").defaultSchema("standalone_app").createSchemas(false).cleanDisabled(true)
                .placeholders(Map.of("instanceId", configuration.getRequiredProperty("standalone.instance-id")))
                .loggers("slf4j").load();
    }
}
