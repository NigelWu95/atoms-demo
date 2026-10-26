package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/** Actual owned HTTP JVM + PG runtime service. The sealed metadata fixture is not a generated application build. */
final class ExecutionRuntimeFixture implements ExecutionCoordinator.WorkerResources {
    final ExecutionSchedulerFixture builds;
    final ExecutionSettings settings;
    final JdbcTemplate jdbc;
    final PlatformTransactionManager transactions;
    final ExecutionToolchain tools;
    final ExecutionDatabaseBinding databases;
    final ExecutionBindingRepository.Target target;
    final ExecutionRunRepository runs;
    final ExecutionRunService service;
    final Path childClasses;
    final AtomicReference<ExecutionRunProcess> process = new AtomicReference<>();
    final AtomicInteger launches = new AtomicInteger();
    ExecutionRuntimeResources runtime;

    ExecutionRuntimeFixture(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, ProjectSourceReadGateway source, ExecutionToolchain tools,
            ExecutionDatabaseBinding databases, ExecutionBindingRepository.Target target) throws Exception {
        this.settings = settings; this.jdbc = jdbc; this.transactions = transactions; this.tools = tools;
        this.databases = databases; this.target = target;
        childClasses = Path.of(ExecutionHealthTest.Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        builds = new ExecutionSchedulerFixture(settings.load().root(), settings, projects, jdbc, transactions);
        runs = new ExecutionRunRepository(jdbc); var identities = new ExecutionMigrationReviewRepository(jdbc);
        service = new ExecutionRunService(settings, runs, identities, transactions, builds.paths, databases,
                new ExecutionMigrationReview(settings, identities, transactions, source, databases));
    }

    @Override public ExecutionRuntimeResources runtime(OwnedProcess.Preflight coordination) {
        assertThat(runtime).isNull();
        var worker = new ExecutionRunWorker(settings, service, runs, transactions, builds.paths, tools, databases, coordination,
                (prepared, run, execution, budget, preflight) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if (process.get() != null) assertThat(process.get().isAlive()).isFalse();
                    var row = runs.get(target.project(), run);
                    assertThat(row.executionPending()).isTrue(); assertThat(row.executionId()).isEqualTo(execution);
                    launches.incrementAndGet();
                    var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                            "--add-modules", "jdk.httpserver", "-cp", childClasses.toString(),
                            ExecutionHealthTest.Child.class.getName(), Integer.toString(target.port()), run.toString(), execution.toString(), "valid")
                            .directory(prepared.directory());
                    command.environment().clear(); // No business credentials or SQL are passed to the HTTP fixture.
                    var owned = ExecutionRunProcess.start(command, run, execution, budget, preflight); process.set(owned); return owned;
                });
        runtime = new ExecutionRuntimeResources(service, worker, coordination); return runtime;
    }
    @Override public void check() throws IOException { builds.check(); }
    @Override public ExecutionArtifact artifact(ExecutionBuild build, String jar, String manifest, OwnedProcess.Budget budget) throws IOException {
        check(); return ExecutionArtifact.openSuccessful(builds.paths, build, jar, manifest, budget);
    }
    @Override public ExecutionRunProcess ownedProcess(UUID run) {
        return runtime == null ? null : runtime.ownedProcess(run);
    }
    @Override public ExecutionBuild run(ExecutionBuild build, BooleanSupplier cancelled, OwnedProcess.Preflight coordination) throws IOException {
        return builds.run(build, cancelled, coordination);
    }
    @Override public void close() throws IOException {
        if (runtime != null && runtime.hasHeldProcesses()) throw new IOException("RUN_PROCESS_UNSETTLED");
        builds.close();
    }

    ExecutionBuild seal(SourceAgentFixture.Base base, SourceTree tree) throws Exception {
        return seal(builds.paths, tools, jdbc, ExecutionBindingContractIT.OWNER, base, tree);
    }
    static ExecutionBuild seal(ExecutionPaths paths, ExecutionToolchain tools, JdbcTemplate jdbc, UUID owner,
            SourceAgentFixture.Base base, SourceTree tree) throws Exception {
        UUID id = UUID.randomUUID(), project = base.projectId();
        String hash = "a".repeat(64);
        jdbc.update("INSERT INTO execution_builds(id,project_id,owner_id,source_version_id,tree_sha256,idempotency_key_sha256,request_sha256,queue_slot) VALUES (?,?,?,?,?,?,?,1)",
                id, project, owner, base.versionId(), tree.sha256(), id.toString().replace("-", "").repeat(2), hash);
        jdbc.update("UPDATE execution_builds SET status='PREPARING',queue_slot=null,worker_slot=1 WHERE id=?", id);
        for (String stage : List.of("INSTALLING", "CHECKING", "PACKAGING")) jdbc.update("UPDATE execution_builds SET status=? WHERE id=?", stage, id);
        var repository = new ExecutionBuildRepository(jdbc); var build = repository.get(project, id);
        var task = paths.createBuild(id); Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(Files.createDirectory(backend.resolve("target")).resolve("app.jar")))) {
            zip.putNextEntry(new ZipEntry(ExecutionMigrations.JAR + "V001__execution_contract_fixture.sql"));
            zip.write(ExecutionRunAdmissionIT.SQL.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var identities = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) identities.put(kind, tools.identity(kind));
        var artifact = ExecutionArtifact.seal(paths, task, build, new ExecutionArtifact.Evidence(identities,
                new ExecutionNpmPolicy.Verified(hash, hash, hash, 1), new ExecutionMavenPolicy.Verified(hash, hash),
                new ExecutionDiagnostics.Frontend(1, 1, 1), new ExecutionJavaReport.Summary(1, 1), new ExecutionPackaging.Summary(1, 1, hash)),
                new ExecutionProcess.Deadline(Duration.ofSeconds(30)), () -> {});
        jdbc.update("UPDATE execution_builds SET status='SUCCEEDED',jar_bytes=?,jar_sha256=?,manifest_sha256=?,worker_slot=null,finished_at=clock_timestamp() WHERE id=?",
                artifact.summary().jarBytes(), artifact.summary().jarSha256(), artifact.summary().manifestSha256(), id);
        return repository.get(project, id);
    }
}
