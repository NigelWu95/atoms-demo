package dev.atoms.demo.projectexecution;

import com.zaxxer.hikari.HikariDataSource;
import dev.atoms.demo.project.ProjectService;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;

/** Test-only coordinator JVM. halt deliberately bypasses finally and shutdown hooks. Never launches npm/Maven. */
public final class ExecutionCrashProbe {
    enum Window { BEFORE_INTENT, LOST_COMPLETION, AFTER_STAGE, AFTER_SEAL, AFTER_COMMIT }
    private static String phase = "configuration";

    public static void main(String[] args) throws Exception {
        try { execute(args); }
        catch (Throwable error) {
            var input = new Properties();
            try (var in = Files.newInputStream(Path.of(args[0]))) { input.load(in); }
            var diagnostic = new StringBuilder(phase);
            for (int i = 0; i < 4 && error != null; i++, error = error.getCause())
                diagnostic.append('\n').append(error.getClass().getName());
            Files.writeString(Path.of(input.getProperty("root")).getParent().resolve("fixture-failure"),
                    diagnostic, StandardOpenOption.CREATE_NEW);
            throw new IllegalStateException("CRASH_PROBE_FAILED");
        }
    }

    private static void execute(String[] args) throws Exception {
        var input = new Properties();
        try (var in = Files.newInputStream(Path.of(args[0]))) { input.load(in); }
        Path root = Path.of(input.getProperty("root")), evidence = root.getParent();
        Window window = Window.valueOf(input.getProperty("window"));
        boolean recovering = args.length == 2 && args[1].equals("recover");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(evidence.resolve(recovering ? "graceful-recovery" : "graceful-shutdown"), "normal exit", StandardOpenOption.CREATE_NEW); }
            catch (Exception ignored) { }
        }));
        var database = new HikariDataSource();
        database.setJdbcUrl(input.getProperty("url")); database.setUsername(input.getProperty("user"));
        database.setPassword(input.getProperty("password")); database.setMaximumPoolSize(3);
        database.setMinimumIdle(0); database.setConnectionTimeout(3000);
        var jdbc = new JdbcTemplate(database); jdbc.setQueryTimeout(3);
        phase = "database";
        if (!ExecutionTestTarget.DATABASE_SCHEMA.equals(
                jdbc.queryForObject("SELECT current_database() || '.' || current_schema()", String.class)))
            throw new IllegalStateException("Unapproved crash fixture database");
        var transactions = new DataSourceTransactionManager(database);
        var projects = new ProjectService(jdbc); var builds = new ExecutionBuildRepository(jdbc);
        UUID owner = UUID.fromString(input.getProperty("owner"));
        var config = new MockEnvironment().withProperty("atoms.project-execution.enabled", "true")
                .withProperty("atoms.project-execution.maintainer-ids", owner.toString());
        for (String key : List.of("root", "node", "npm", "java", "maven", "policy"))
            config.withProperty("atoms.project-execution." + key, key.equals("root") ? root.toString() : evidence.resolve(key).toString());
        var settings = new ExecutionSettings(config);
        // Intentionally left to the operating system on halt; the parent must actually reacquire both leases.
        phase = "coordination";
        var lease = new ExecutionCoordinatorLease(database);
        phase = "directory";
        var paths = new ExecutionPaths(root, List.of());
        if (recovering) {
            phase = "recovery";
            var existing = builds.get(UUID.fromString(input.getProperty("project")), UUID.fromString(input.getProperty("build")));
            var result = new ExecutionRecovery(settings, builds, jdbc, transactions, lease, () -> true).reconcile(existing);
            paths.close(); lease.close(); database.close();
            Files.writeString(evidence.resolve("restarted"), result.status(), StandardOpenOption.CREATE_NEW);
            return;
        }
        phase = "claim";
        var build = new ExecutionBuildClaim(settings, projects, builds, jdbc, transactions).claim(owner,
                UUID.fromString(input.getProperty("project")), UUID.fromString(input.getProperty("build")));
        var task = paths.createBuild(build.id());
        Path frontend = ExecutionRootLease.directory(paths.project(task).resolve("frontend"));
        Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
        var journal = new ExecutionBuildJournal(settings, projects, jdbc, transactions, build, paths, task);
        if (window == Window.BEFORE_INTENT) halt(evidence, window);
        if (window == Window.LOST_COMPLETION) {
            UUID execution = UUID.randomUUID();
            journal.begin(NPM_CI, execution);
            Files.writeString(evidence.resolve("request.tmp"), execution.toString(), StandardOpenOption.CREATE_NEW);
            Files.move(evidence.resolve("request.tmp"), evidence.resolve("request"), StandardCopyOption.ATOMIC_MOVE);
            long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!Files.exists(evidence.resolve("executed"))) {
                if (System.nanoTime() >= end) throw new IllegalStateException("Fixture execution timed out");
                Thread.sleep(25);
            }
            // The external executor finished, but this JVM dies without receiving/persisting its stopped result.
            halt(evidence, window);
        }
        var metadata = evidence();
        for (var operation : List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD, MAVEN_PACKAGE)) {
            UUID execution = UUID.randomUUID();
            if (operation == MAVEN_PACKAGE) journal.begin(execution); else journal.begin(operation, execution);
            Path cwd = operation == MAVEN_PACKAGE ? backend : frontend, java = Path.of(System.getProperty("java.home"));
            var result = new ExecutionProcess().run(new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-version"),
                    cwd, cwd, cwd, java, java.resolve("bin")), budget(), () -> false, build.id(), execution,
                    () -> { if (!lease.healthy()) throw new java.io.IOException("EXECUTION_COORDINATOR_UNAVAILABLE"); });
            // Test counts describe a byte-sealing fixture, not native build acceptance (covered by EX11/12).
            if (operation == MAVEN_PACKAGE) journal.finish(new ExecutionJava.Stage(execution, null, result.stopped(), result.summary(), metadata.javaTests()));
            else journal.finish(new ExecutionFrontend.Stage(operation, execution, null, result.stopped(), result.summary(),
                    operation == UNIT_TEST ? metadata.frontend() : null));
            if (window == Window.AFTER_STAGE) halt(evidence, window);
        }
        Files.createDirectory(backend.resolve("target"));
        Files.write(backend.resolve("target/app.jar"), new byte[]{80, 75, 3, 4, 1, 2, 3}, StandardOpenOption.CREATE_NEW);
        var artifact = ExecutionArtifact.seal(paths, task, build, metadata, budget(), () -> {});
        if (window == Window.AFTER_SEAL) halt(evidence, window);
        new ExecutionBuildSettlement(settings, projects, jdbc, transactions).succeed(build, artifact, budget());
        halt(evidence, window);
    }

    private static ExecutionArtifact.Evidence evidence() {
        String hash = "a".repeat(64);
        var tools = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) tools.put(kind, new ExecutionToolFiles.Summary(hash, 1, 20));
        return new ExecutionArtifact.Evidence(tools, new ExecutionNpmPolicy.Verified(hash, hash, hash, 50),
                new ExecutionMavenPolicy.Verified(hash, hash), new ExecutionDiagnostics.Frontend(1, 2, 3),
                new ExecutionJavaReport.Summary(1, 2), new ExecutionPackaging.Summary(2, 100, hash));
    }
    private static ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    private static void halt(Path evidence, Window window) throws Exception {
        Files.writeString(evidence.resolve("halt-window"), window.name(), StandardOpenOption.CREATE_NEW);
        Runtime.getRuntime().halt(71);
    }
}
