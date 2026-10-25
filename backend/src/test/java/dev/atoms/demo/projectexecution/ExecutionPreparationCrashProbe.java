package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** Test-only JVM: commits a controlled source change, then halts before preparation settlement. No npm or model. */
public final class ExecutionPreparationCrashProbe {
    private static String phase = "configuration";

    public static void main(String[] args) throws Exception {
        var input = new Properties();
        try (var stream = Files.newInputStream(Path.of(args[0]))) { input.load(stream); }
        Path evidence = Path.of(input.getProperty("evidence"));
        try { execute(input, evidence); }
        catch (Throwable error) {
            var diagnostic = new StringBuilder(phase);
            for (int i = 0; i < 4 && error != null; i++, error = error.getCause())
                diagnostic.append('\n').append(error.getClass().getName());
            write(evidence.resolve("fixture-failure"), diagnostic.toString());
            throw new IllegalStateException("PREPARATION_CRASH_PROBE_FAILED");
        }
    }

    private static void execute(Properties input, Path evidence) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { write(evidence.resolve("graceful-shutdown"), "normal exit"); }
            catch (Exception ignored) { }
        }));
        try (var database = new HikariDataSource()) {
            database.setJdbcUrl(input.getProperty("url")); database.setUsername(input.getProperty("user"));
            database.setPassword(input.getProperty("password")); database.setMaximumPoolSize(5);
            database.setMinimumIdle(0); database.setConnectionTimeout(3000);
            var jdbc = new JdbcTemplate(database); jdbc.setQueryTimeout(3);
            phase = "database";
            assertThat(jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class))
                    .isEqualTo(ExecutionTestTarget.DATABASE_SCHEMA);
            var transactions = new DataSourceTransactionManager(database);
            var projects = new ProjectService(jdbc);
            var config = configuration(evidence, UUID.fromString(input.getProperty("owner")));
            var settings = new ExecutionSettings(config);
            // On halt these leases are released by the OS/connection loss, not by close/finally.
            try (var source = new SourceAgentFixture(jdbc, projects, transactions, evidence);
                 var lease = new ExecutionCoordinatorLease(database);
                 var paths = new ExecutionPaths(settings.load().root(), List.of(evidence.resolve("source"), evidence.resolve("work")))) {
                source.start(database);
                UUID owner = UUID.fromString(input.getProperty("owner"));
                phase = "source-base";
                var base = source.create(owner);
                var files = new ArrayList<>(source.snapshot(owner, base.projectId(), base.versionId()).files());
                files.add(new SourceTree.File("frontend/package.json", Files.readAllBytes(Path.of(input.getProperty("manifest"))), 0100644));
                base = source.accept(owner, base, new ProjectSourcePolicy().validate(files));
                var repository = new DependencyPreparationRepository(jdbc);
                var service = new DependencyPreparationService(settings, repository, projects, source.readGateway(), source.gateway(),
                        source.environment(), config, transactions);
                phase = "admission";
                var view = service.submit(owner, base.projectId(), new DependencyPreparationService.Input(base.versionId(), base.revision()), "crash-fixture");
                var edit = service.handle(view.id());
                var task = new DependencyPreparationClaim(settings, projects, repository, new ExecutionBuildRepository(jdbc),
                        source.gateway(), jdbc, transactions).claim(owner, base.projectId(), view.id(), edit);
                write(evidence.resolve("task"), task.projectId() + "\n" + task.id() + "\n");
                var flow = new DependencyPreparationLifecycle(settings, projects, jdbc, transactions, repository, service, source.gateway(), task, edit);
                source.gateway().prepareEdit(edit);
                Path work = source.gateway().workPath(edit);
                phase = "owned-execution";
                UUID execution = UUID.randomUUID(); flow.begin(execution);
                var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-version").directory(work.toFile());
                command.environment().clear();
                try {
                    var stopped = new OwnedProcess().runCommand(command, () -> Duration.ofSeconds(10), () -> false, task.id(), execution,
                            stream -> stream.readNBytes(2048), stream -> { stream.readNBytes(2048); return null; }, () -> {}).stopped();
                    flow.stopped(stopped);
                } catch (OwnedProcess.Failure error) {
                    if (error.stopped() != null) flow.stopped(error.stopped());
                    throw error;
                }
                phase = "source-publication";
                Files.copy(Path.of(input.getProperty("lock")), work.resolve("frontend/package-lock.json"));
                flow.accepting();
                var accepted = (ProjectSourceEditGateway.Submitted) source.gateway().sealAndAccept(edit, "crash-publication", candidate -> {});
                var committed = accepted.completion().get(30, TimeUnit.SECONDS);
                assertThat(committed.status()).isEqualTo("COMMITTED");
                assertThat(repository.get(task.projectId(), task.id()).sourceOperationId()).isNull();
                paths.checkCapacity(); assertThat(lease.healthy()).isTrue();
                write(evidence.resolve("committed"), committed.id() + "\n" + committed.resultVersionId() + "\n");
                write(evidence.resolve("halt-window"), "AFTER_SOURCE_COMMIT_BEFORE_TASK_BINDING");
                Runtime.getRuntime().halt(173);
            }
        }
    }

    static MockEnvironment configuration(Path evidence, UUID owner) {
        var config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString())
                .withProperty("atoms.project-execution.root", evidence.resolve("execution").toString())
                .withProperty("atoms.project-execution.policy", evidence.resolve("policy.json").toString());
        for (String tool : List.of("node", "npm", "java", "maven"))
            config.withProperty("atoms.project-execution." + tool, evidence.resolve("unused-" + tool).toString());
        return config;
    }

    static void write(Path path, String content) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, content);
    }
}
