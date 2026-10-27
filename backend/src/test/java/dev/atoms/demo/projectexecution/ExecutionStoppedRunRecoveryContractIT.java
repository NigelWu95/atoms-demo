package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.SourceAgentFixture;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Explicit one-time operator action, gated to the exact user-approved stopped run. Never part of ordinary regression. */
@Tag("project-execution-contract") @ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionStoppedRunRecoveryContractIT {
    static final UUID TARGET = UUID.fromString("dab70c67-efd9-4ff9-a155-c6069afaa4cb");
    private static final UUID OWNER = ExecutionApplicationFixture.OWNER, A = ExecutionApplicationFixture.A, B = ExecutionApplicationFixture.B;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;

    @Test void acceptedRestartMaintenanceOnlyFinishesTheSelectedStoppedRunWithoutChangingApplications() throws Exception {
        assertThat(System.getProperty("execution.accept-stopped-run")).isEqualTo(TARGET.toString());
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)).isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE status IN ('PREPARING','CANDIDATE_READY','NEEDS_RECONCILIATION')", Integer.class)).isZero();
        Path parent = directory("execution.contract-directory"), application = directory("execution.application-directory");
        assertThat(application.getParent()).isEqualTo(parent);
        Path sourceRoot = directory("execution.runtime-fixture-root"), config = directory("execution.test-config-directory");
        var protectedRoots = List.of(sourceRoot.resolve("source"), sourceRoot.resolve("work"));
        var businessFiles = Map.of(A, config.resolve(ExecutionBindingContractIT.A_DB + ".properties"),
                B, config.resolve(ExecutionBindingContractIT.B_DB + ".properties"));
        var testFiles = Map.of(A, config.resolve(ExecutionBindingContractIT.A_TEST + ".properties"),
                B, config.resolve("atoms_exec_b_test_20261020.properties"));
        var business = ExecutionDatabaseBinding.load(businessFiles, protectedRoots, Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        var tests = ExecutionTestDatabase.load(testFiles, protectedRoots, Set.of("atoms_demo", "atoms_demo_test"), Set.of("postgres", "atoms_test"));
        business.requireSeparateFrom(tests);
        var testBefore = Map.of(A, tests.verify(A, budget()), B, tests.verify(B, budget()));
        var before = snapshots(businessFiles, protectedRoots);
        assertThat(before.get(A).rows()).isEqualTo(6); assertThat(before.get(B).rows()).isEqualTo(3);
        String metadata = otherMetadata();
        for (var project : List.of(A, B)) assertThat(business.verify(project, budget())).isEqualTo(new ExecutionBindingRepository(jdbc).get(project).target());
        Path evidence = application.resolve("delivery-startup-recovery-" + TARGET);
        if (!Files.exists(evidence)) Files.createDirectory(evidence, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ExecutionRootLease.noLinks(evidence);
        assertThat(Files.getPosixFilePermissions(evidence)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        var environment = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", OWNER.toString())
                .withProperty("atoms.project-execution.root", application.resolve("execution").toString())
                .withProperty("atoms.project-execution.policy", application.resolve("approval.json").toString());
        for (String tool : List.of("node", "npm", "java", "maven"))
            environment.withProperty("atoms.project-execution." + tool, Path.of(System.getProperty("execution.contract-" + tool)).toString());
        var settings = new ExecutionSettings(environment);
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, sourceRoot)) {
            source.start(database);
            var read = source.readGateway();
            try (var maintenance = ExecutionStoppedRunMaintenance.acquire(settings, database, read::readyForExecution, protectedRoots, OWNER, A, TARGET)) {
                var service = new ExecutionRunReconciliation(settings, jdbc, transactions, read, business, maintenance);
                Path viewFile = evidence.resolve("confirmation-view.json");
                ExecutionRunReconciliationRepository.View view;
                if (Files.exists(viewFile)) {
                    ExecutionRootLease.noLinks(viewFile);
                    assertThat(Files.getPosixFilePermissions(viewFile)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
                    assertThat(Files.size(viewFile)).isLessThan(16384);
                    view = JSON.readValue(Files.readAllBytes(viewFile), ExecutionRunReconciliationRepository.View.class);
                } else {
                    view = service.inspectStoppedFailure(OWNER, A, TARGET);
                    Files.write(Files.createFile(viewFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))), JSON.writeValueAsBytes(view));
                }
                assertThat(view.run().id()).isEqualTo(TARGET); assertThat(view.run().ownerId()).isEqualTo(OWNER);
                assertThat(view.run().projectId()).isEqualTo(A); assertThat(view.run().errorCode()).isEqualTo("RUN_START_TIMEOUT");
                assertThat(view.run().buildId()).isEqualTo(UUID.fromString("79815b82-d390-46b6-acbe-832932118b16"));
                assertThat(view.run().sourceVersionId()).isEqualTo(UUID.fromString("a3d2b3eb-65cc-4c5f-9ea2-1f899b90ac7c"));
                String key = "delivery-startup-recovery-" + TARGET;
                assertThat(snapshots(businessFiles, protectedRoots)).isEqualTo(before);
                assertThat(otherMetadata()).isEqualTo(metadata);
                var audit = service.acknowledgeStoppedFailure(OWNER, view, key);
                assertThat(service.acknowledgeStoppedFailure(OWNER, view, key)).isEqualTo(audit);
                var ended = new ExecutionRunRepository(jdbc).get(A, TARGET);
                assertThat(ended.status()).isEqualTo("FAILED"); assertThat(ended.finishedAt()).isNotNull();
                assertThat(ended.executionId()).isEqualTo(view.run().executionId());
                assertThat(ended.stoppedSha256()).isEqualTo(view.run().stoppedSha256()); assertThat(ended.errorCode()).isEqualTo(view.run().errorCode());
                assertThat(ended.executionPending()).isFalse();
                assertThat(snapshots(businessFiles, protectedRoots)).isEqualTo(before);
                assertThat(otherMetadata()).isEqualTo(metadata);
                for (var project : List.of(A, B)) assertThat(tests.verify(project, budget())).isEqualTo(testBefore.get(project));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_run_reconciliations WHERE run_id=?", Integer.class, TARGET)).isEqualTo(1);
                Files.writeString(evidence.resolve("result.json"), JSON.writeValueAsString(Map.of("run", TARGET, "audit", audit.id(), "history", audit.historySha256(),
                        "businessBeforeAndAfter", before, "otherMetadataUnchanged", true)));
                Files.setPosixFilePermissions(evidence.resolve("result.json"), PosixFilePermissions.fromString("rw-------"));
                System.out.printf("delivery_recovery_passed run=%s audit=%s history=%s preservedA=%d preservedB=%d evidence=%s%n",
                        TARGET, audit.id(), audit.historySha256(), before.get(A).rows(), before.get(B).rows(), evidence);
            }
        }
    }

    private record Snapshot(int rows, String rowsSha256, String schemaSha256, String historySha256) {}
    private Map<UUID, Snapshot> snapshots(Map<UUID, Path> files, List<Path> protectedRoots) throws Exception {
        var result = new HashMap<UUID, Snapshot>();
        for (var entry : files.entrySet()) {
            var config = ExecutionPrivateConfig.readBusiness(entry.getValue(), protectedRoots);
            try (var connection = DriverManager.getConnection(config.jdbcUrl(), config.inspectionProperties(5))) {
                connection.setReadOnly(true); connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    var history = ExecutionMigrationHistory.read(connection, budget());
                    var rows = new ArrayList<String>();
                    try (var rs = statement.executeQuery("SELECT row_to_json(t)::text FROM generated_app.todos t ORDER BY id LIMIT 1001")) {
                        while (rs.next()) rows.add(rs.getString(1));
                    }
                    assertThat(rows.size()).isLessThanOrEqualTo(1000);
                    var schema = new ArrayList<String>();
                    try (var rs = statement.executeQuery("SELECT row_to_json(t)::text FROM information_schema.columns t WHERE table_schema='generated_app' ORDER BY table_name,ordinal_position")) {
                        while (rs.next()) schema.add(rs.getString(1));
                    }
                    result.put(entry.getKey(), new Snapshot(rows.size(), hash(rows), hash(schema), history.sha256()));
                } finally { connection.rollback(); }
            }
        }
        return Map.copyOf(result);
    }
    private String otherMetadata() {
        return jdbc.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'')) FROM execution_runs t WHERE id<>?", String.class, TARGET)
                + jdbc.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'')) FROM execution_database_bindings t", String.class)
                + jdbc.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'')) FROM source_versions t", String.class);
    }
    private static String hash(List<String> rows) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(rows))); }
    private static Path directory(String property) throws Exception {
        Path path = Path.of(System.getProperty(property)).toRealPath(); assertThat(Files.isDirectory(path)).isTrue(); return path;
    }
    private static dev.atoms.demo.execution.OwnedProcess.Budget budget() { return new ExecutionProcess.Deadline(java.time.Duration.ofSeconds(30)); }
}
