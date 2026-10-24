package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real JVM halt and fresh coordinator recovery; source change is a controlled fixture, not npm acceptance. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(properties = {"atoms.project-execution.enabled=false", "atoms.source.enabled=false", "atoms.generation.worker-enabled=false"})
class ExecutionPreparationCrashIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager transactions;
    @Autowired HikariDataSource database;
    @TempDir Path temporary;

    @Test void actualHaltAfterSourceCommitRecoversItsVersionWithoutRunningToolsAgain() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                .isEqualTo("atoms_demo_test.atoms_verify_20261024_ex16");
        UUID owner = UUID.randomUUID(); jdbc.update("INSERT INTO guest_users(id) VALUES (?)", owner);
        // Evidence survives a failed test, while the credential file is owned by JUnit's temporary directory.
        Path evidence = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), "atoms-ex16-preparation-crash-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path processDirectory = ExecutionRootLease.directory(evidence.resolve("coordinator"));
        Path policy = evidence.resolve("policy.json");
        Files.copy(Path.of("src/main/resources/projectexecution/seed-dependency-policy.json"), policy);
        Files.setPosixFilePermissions(policy, PosixFilePermissions.fromString("rw-------"));
        var input = new Properties();
        input.setProperty("url", database.getJdbcUrl()); input.setProperty("user", database.getUsername());
        input.setProperty("password", database.getPassword()); input.setProperty("evidence", evidence.toString());
        input.setProperty("owner", owner.toString());
        input.setProperty("manifest", Path.of("../templates/full-project-v1/frontend/package.json").toRealPath().toString());
        input.setProperty("lock", Path.of("../templates/full-project-v1/frontend/package-lock.json").toRealPath().toString());
        Path credentials = Files.createFile(temporary.resolve("crash-input.properties"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(credentials)) { input.store(output, "isolated test input"); }
        Path java = Path.of(System.getProperty("java.home"));
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(File.pathSeparator)).map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        UUID childTask = UUID.randomUUID(), childExecution = UUID.randomUUID();
        var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"),
                List.of("-cp", classpath, ExecutionPreparationCrashProbe.class.getName(), credentials.toString()),
                processDirectory, processDirectory, processDirectory, java, java.resolve("bin"));
        try {
            new ExecutionProcess().run(invocation, new ExecutionProcess.Deadline(Duration.ofSeconds(120)),
                    () -> false, childTask, childExecution, () -> {});
            fail("Crash probe must halt without a normal shutdown");
        } catch (OwnedProcess.Failure failure) {
            assertThat(failure.stopped()).isNotNull();
            assertThat(failure.stopped().matches(childTask, childExecution, processDirectory)).isTrue();
        }
        assertThat(evidence.resolve("fixture-failure")).as(Files.exists(evidence.resolve("fixture-failure"))
                ? Files.readString(evidence.resolve("fixture-failure")) : "No fixture failure").doesNotExist();
        assertThat(Files.readString(evidence.resolve("halt-window"))).isEqualTo("AFTER_SOURCE_COMMIT_BEFORE_TASK_BINDING");
        assertThat(evidence.resolve("graceful-shutdown")).doesNotExist();
        var ids = Files.readAllLines(evidence.resolve("task"));
        UUID project = UUID.fromString(ids.get(0)), taskId = UUID.fromString(ids.get(1));
        var committed = Files.readAllLines(evidence.resolve("committed"));
        var repository = new DependencyPreparationRepository(jdbc);
        var before = repository.get(project, taskId);
        assertThat(before.status()).isEqualTo("ACCEPTING");
        assertThat(before.sourceOperationId()).isNull(); assertThat(before.executionPending()).isFalse();
        assertThat(jdbc.queryForMap("SELECT status,execution_pending,stopped_sha256 IS NOT NULL stopped FROM source_edit_leases WHERE id=?", before.editLeaseId()))
                .containsEntry("status", "ACTIVE").containsEntry("execution_pending", false).containsEntry("stopped", true);
        var config = ExecutionPreparationCrashProbe.configuration(evidence, owner);
        var settings = new ExecutionSettings(config);
        var launches = new AtomicInteger();
        try (var source = new SourceAgentFixture(jdbc, projects, transactions, evidence)) {
            source.start(database);
            var service = new DependencyPreparationService(settings, repository, projects, source.readGateway(), source.gateway(),
                    source.environment(), config, transactions);
            assertThat(service.handle(taskId)).isNull();
            // Both starts must reacquire the crashed JVM's real filesystem and database leases.
            DependencyPreparationRepository.Task first = null;
            for (int round = 0; round < 2; round++) {
                try (var coordinator = new ExecutionCoordinator(settings, new ExecutionBuildRepository(jdbc), jdbc, transactions, database,
                        source.readGateway()::readyForExecution, () -> {
                            var paths = new ExecutionPaths(settings.load().root(), List.of(evidence.resolve("source"), evidence.resolve("work")));
                            return new ExecutionCoordinator.WorkerResources() {
                                public void check() throws IOException { paths.checkCapacity(); }
                                public ExecutionBuild run(ExecutionBuild build, BooleanSupplier cancel, OwnedProcess.Preflight control) {
                                    launches.incrementAndGet(); throw new AssertionError("Recovery must not run a build");
                                }
                                public DependencyPreparationRepository.Task runPreparation(DependencyPreparationRepository.Task task,
                                        BooleanSupplier cancel, OwnedProcess.Preflight control) {
                                    launches.incrementAndGet(); throw new AssertionError("Recovery must not run preparation again");
                                }
                                public void close() throws IOException { paths.close(); }
                            };
                        }, service, source.gateway())) {
                    coordinator.start(); assertThat(coordinator.isRunning()).isTrue();
                    var result = repository.get(project, taskId);
                    assertThat(result.status()).isEqualTo("SOURCE_SAVED");
                    assertThat(result.sourceOperationId()).isEqualTo(UUID.fromString(committed.get(0)));
                    assertThat(result.resultVersionId()).isEqualTo(UUID.fromString(committed.get(1)));
                    assertThat(result.errorCode()).isNull();
                    if (first == null) first = result; else assertThat(result).isEqualTo(first);
                }
            }
            assertThat(launches).hasValue(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, project)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE edit_lease_id=?", Integer.class, before.editLeaseId())).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT status,execution_pending FROM source_edit_leases WHERE id=?", before.editLeaseId()))
                    .containsEntry("status", "RELEASED").containsEntry("execution_pending", false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_task_slots WHERE task_id=?", Integer.class, taskId)).isZero();
            assertThat(service.handle(taskId)).isNull();
            assertThat(source.changedPaths(owner, project, before.baseVersionId(), first.resultVersionId()))
                    .containsExactlyEntriesOf(Map.of("frontend/package-lock.json", "ADDED"));
            ExecutionPreparationCrashProbe.write(evidence.resolve("verified"), taskId + "\nSOURCE_SAVED\n");
        }
    }
}
