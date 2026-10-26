package dev.atoms.demo.projectexecution;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in approved business databases only. SQL fixtures remain in rolled-back transactions. */
@Tag("project-execution-contract")
class ExecutionMigrationHistoryIT {
    Path directory;
    static final UUID A = ExecutionBindingContractIT.A, B = ExecutionBindingContractIT.B;
    final Map<UUID, ExecutionMigrationHistory.Snapshot> businessBefore = new HashMap<>();
    @BeforeEach void explicitTarget() throws Exception {
        String value = System.getProperty("execution.test-config-directory");
        assertThat(value).as("Explicit approved database configuration directory").isNotBlank(); directory = Path.of(value);
        for (UUID project : List.of(A, B)) businessBefore.put(project, registry().history(project, budget()));
    }
    @AfterEach void businessHistoriesRemainUnchanged() throws Exception {
        for (var entry : businessBefore.entrySet()) assertThat(registry().history(entry.getKey(), budget())).isEqualTo(entry.getValue());
    }
    @Test void completeInspectionReadsExistingBusinessAndEmptyTestSchemasWithoutWriting() throws Exception {
        var registry = registry();
        for (UUID project : List.of(A, B)) {
            var first = registry.history(project, budget());
            assertThat(first).isEqualTo(businessBefore.get(project));
            assertThat(registry.history(project, budget())).isEqualTo(first);
            assertThat(first.binding()).isEqualTo(registry.verify(project, budget()));
        }
        try (var db = fixture()) {
            var empty = ExecutionMigrationHistory.read(db, budget());
            assertThat(empty.exists()).isFalse(); assertThat(empty.objects()).isZero(); assertThat(empty.applied()).isEmpty();
        }
    }
    @Test void readsRealPostgresHistoryAndDetectsChangedFailedAndPartialUpgradeStates() throws Exception {
        try (var db = fixture()) {
            try {
                createHistory(db);
                insert(db, 1, "001", 42, true);
                var first = ExecutionMigrationHistory.read(db, budget());
                assertThat(first.applied()).hasSize(1); assertThat(first.applied().get(0).checksum()).isEqualTo(42);
                assertThat(first.exists()).isTrue(); assertThat(first.objects()).isPositive();
                try (var command = db.createStatement()) { command.executeUpdate("UPDATE generated_app.flyway_schema_history SET checksum=43"); }
                var changed = ExecutionMigrationHistory.read(db, budget()); assertThat(changed.sha256()).isNotEqualTo(first.sha256());
                insert(db, 2, "002", 44, false);
                var failed = ExecutionMigrationHistory.read(db, budget()); assertThat(failed.applied().get(1).success()).isFalse();
                var target = List.of(new ExecutionMigrations.Migration(1,"V001__first.sql","first",43,"a".repeat(64)),
                        new ExecutionMigrations.Migration(2,"V002__first.sql","first",44,"b".repeat(64)));
                assertThatThrownBy(() -> ExecutionMigrationPolicy.evaluate(target, failed.exists(), failed.objects(), failed.applied()))
                        .hasMessage("RUN_MIGRATION_INCOMPATIBLE");
            } finally { db.rollback(); }
        }
    }
    @Test void refusesAViewInsteadOfHistoryAndDetectsNonTableSchemaObjects() throws Exception {
        try (var db = fixture()) {
            try {
                try (var command = db.createStatement()) { command.execute("CREATE VIEW generated_app.flyway_schema_history AS SELECT 1 AS installed_rank"); }
                assertThatThrownBy(() -> ExecutionMigrationHistory.read(db, budget())).hasMessage("RUN_MIGRATION_HISTORY_INVALID");
            } finally { db.rollback(); }
            try {
                try (var command = db.createStatement()) { command.execute("CREATE TYPE generated_app.ex18_marker AS ENUM ('one')"); }
                var state = ExecutionMigrationHistory.read(db, budget());
                assertThat(state.exists()).isFalse(); assertThat(state.objects()).isPositive();
            } finally { db.rollback(); }
        }
    }
    @Test void rejectsOversizedHistoryAndMalformedHistoryColumns() throws Exception {
        try (var db = fixture()) {
            try {
                createHistory(db);
                try (var command = db.createStatement()) {
                    command.execute("INSERT INTO generated_app.flyway_schema_history SELECT n,n::text,'first','SQL','V001__first.sql',42,current_user,clock_timestamp(),1,true FROM generate_series(1,129) n");
                }
                assertThatThrownBy(() -> ExecutionMigrationHistory.read(db, budget())).hasMessage("RUN_MIGRATION_HISTORY_INVALID");
            } finally { db.rollback(); }
            try {
                try (var command = db.createStatement()) { command.execute("CREATE TABLE generated_app.flyway_schema_history(installed_rank integer)"); }
                assertThatThrownBy(() -> ExecutionMigrationHistory.read(db, budget())).hasMessage("RUN_MIGRATION_HISTORY_INVALID");
            } finally { db.rollback(); }
        }
    }
    @Test void boundsHistoryTextAndRefusesRowSecurityWithoutMutatingTheRejectedHistory() throws Exception {
        try (var db = fixture()) {
            try {
                createHistory(db); insert(db,1,"001",42,true);
                try (var command = db.createStatement()) { command.execute("UPDATE generated_app.flyway_schema_history SET script=repeat('x',500)"); }
                assertThatThrownBy(() -> ExecutionMigrationHistory.read(db, budget())).hasMessage("RUN_MIGRATION_HISTORY_INVALID");
                try (var command = db.createStatement(); var row = command.executeQuery("SELECT length(script),checksum FROM generated_app.flyway_schema_history")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(500); assertThat(row.getInt(2)).isEqualTo(42);
                }
            } finally { db.rollback(); }
            try {
                createHistory(db);
                try (var command = db.createStatement()) { command.execute("ALTER TABLE generated_app.flyway_schema_history ENABLE ROW LEVEL SECURITY"); }
                assertThatThrownBy(() -> ExecutionMigrationHistory.read(db, budget())).hasMessage("RUN_MIGRATION_HISTORY_INVALID");
            } finally { db.rollback(); }
        }
    }
    @Test void schemaWithOnlyACollationIsNotAnEmptyInitializationTarget() throws Exception {
        try (var db=fixture()) {
            try {
                try (var command=db.createStatement()) { command.execute("CREATE COLLATION generated_app.ex18_collation FROM pg_catalog.\"C\""); }
                var state=ExecutionMigrationHistory.read(db,budget());
                assertThat(state.exists()).isFalse(); assertThat(state.objects()).isPositive();
                var target=ExecutionMigrations.catalog(Map.of("V001__first.sql","CREATE TABLE first_table(id int);".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                assertThatThrownBy(() -> ExecutionMigrationPolicy.evaluate(target,state.exists(),state.objects(),state.applied()))
                        .hasMessage("RUN_MIGRATION_INCOMPATIBLE");
            } finally { db.rollback(); }
        }
    }
    private Connection fixture() throws Exception {
        // Synthetic history is confined to the already-approved test DB and rolled back, never the populated business DB.
        var config = ExecutionPrivateConfig.read(file("atoms_exec_b_test_20261020"), List.of());
        var db = DriverManager.getConnection(config.jdbcUrl(), config.inspectionProperties(5));
        try {
            db.setReadOnly(false); db.setAutoCommit(false);
            assertThat(ExecutionMigrationHistory.read(db, budget()).objects()).as("Never overwrite an occupied test schema").isZero();
            return db;
        } catch (Exception | AssertionError failure) { db.close(); throw failure; }
    }
    private void createHistory(Connection db) throws SQLException {
        try (var command = db.createStatement()) {
            command.execute("""
                CREATE TABLE generated_app.flyway_schema_history (
                  installed_rank integer NOT NULL, version varchar(50), description varchar(200) NOT NULL,
                  type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum integer,
                  installed_by varchar(100) NOT NULL, installed_on timestamp NOT NULL, execution_time integer NOT NULL, success boolean NOT NULL)
                """);
        }
    }
    private void insert(Connection db, int rank, String version, int checksum, boolean success) throws SQLException {
        try (var command = db.prepareStatement("INSERT INTO generated_app.flyway_schema_history VALUES (?,?,'first','SQL',?,?,current_user,clock_timestamp(),1,?)")) {
            command.setInt(1,rank); command.setString(2,version); command.setString(3,"V"+version+"__first.sql"); command.setInt(4,checksum); command.setBoolean(5,success); command.executeUpdate();
        }
    }
    private ExecutionDatabaseBinding registry() throws Exception {
        return ExecutionDatabaseBinding.load(Map.of(A,file("atoms_exec_a_app_20261020"),B,file("atoms_exec_b_app_20261020")), List.of(),
                Set.of("atoms_demo","atoms_demo_test","atoms_exec_a_test_20261020","atoms_exec_b_test_20261020"),
                Set.of("postgres","atoms_test","atoms_exec_a_test_20261020","atoms_exec_b_test_20261020"));
    }
    private Path file(String name) { return directory.resolve(name+".properties"); }
    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(15)); }
}
