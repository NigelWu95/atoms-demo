package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Real owned JVM and journal, never a successful npm/Maven build substitute. */
final class ExecutionSchedulerFixture implements ExecutionCoordinator.WorkerResources {
    final ExecutionPaths paths;
    final ExecutionSettings settings;
    final ProjectService projects;
    final JdbcTemplate jdbc;
    final PlatformTransactionManager transactions;
    final ExecutionBuildRepository builds;
    final AtomicInteger launches = new AtomicInteger();
    final Map<UUID, OwnedProcess.Stopped> stopped = new ConcurrentHashMap<>();
    final Map<UUID, ExecutionBuildJournal> journals = new ConcurrentHashMap<>();
    final Map<UUID, ExecutionFrontend.Stage> receipts = new ConcurrentHashMap<>();
    volatile boolean closed, loseResult;

    ExecutionSchedulerFixture(Path root, ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
                              PlatformTransactionManager transactions) throws IOException {
        this.paths = new ExecutionPaths(root, List.of()); this.settings = settings;
        this.projects = projects; this.jdbc = jdbc; this.transactions = transactions;
        builds = new ExecutionBuildRepository(jdbc);
    }

    @Override public void check() throws IOException { paths.checkedRoot(); }

    @Override public ExecutionBuild run(ExecutionBuild candidate, BooleanSupplier cancelled,
                                        OwnedProcess.Preflight coordination) throws IOException {
        coordination.check();
        var build = new ExecutionBuildClaim(settings, projects, builds, jdbc, transactions)
                .claim(candidate.ownerId(), candidate.projectId(), candidate.id());
        var task = paths.createBuild(build.id());
        Path frontend = Files.createDirectory(paths.project(task).resolve("frontend"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var journal = new ExecutionBuildJournal(settings, projects, jdbc, transactions, build, paths, task);
        journals.put(build.id(), journal);
        UUID execution = UUID.randomUUID();
        journal.begin(ExecutionToolchain.Operation.NPM_CI, execution);
        Path java = Path.of(System.getProperty("java.home"));
        Path classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toRealPath();
        OwnedProcess.Stopped proof = null;
        ExecutionProcess.Output output = null;
        String code = "EXECUTION_PREFLIGHT_FAILED"; // A released fixture ends as a controlled failure, never SUCCEEDED.
        try {
            launches.incrementAndGet();
            var result = new ExecutionProcess().run(new ExecutionProcess.Invocation(java.resolve("bin/java"),
                    List.of("-cp", classes.toString(), Child.class.getName()), frontend, frontend, frontend, java, frontend),
                    new ExecutionProcess.Deadline(Duration.ofSeconds(60)),
                    () -> cancelled.getAsBoolean() || builds.get(build.projectId(), build.id()).cancelRequested(),
                    build.id(), execution, coordination);
            proof = result.stopped(); output = result.summary();
        } catch (OwnedProcess.Failure failure) {
            proof = failure.stopped(); code = ExecutionFrontend.failureCode(failure);
        }
        var receipt = new ExecutionFrontend.Stage(ExecutionToolchain.Operation.NPM_CI, execution, code, proof, output, null);
        if (proof != null) stopped.put(build.id(), proof);
        receipts.put(build.id(), receipt);
        if (loseResult) throw new IOException("EXECUTION_STATE_UNAVAILABLE");
        boolean interrupted = Thread.interrupted();
        try { journal.finish(receipt); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        return builds.get(build.projectId(), build.id());
    }

    void recordHeldStop(ExecutionBuild build) throws IOException {
        var receipt = receipts.get(build.id());
        if (receipt == null || receipt.stopped() == null) throw new IOException("Fixture has no owned stop receipt");
        journals.get(build.id()).finish(receipt);
    }

    @Override public void close() throws IOException { paths.close(); closed = true; }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of("started"), Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW);
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!Files.exists(Path.of("release"))) {
                if (System.nanoTime() >= end) System.exit(23);
                Thread.sleep(25);
            }
        }
    }
}
