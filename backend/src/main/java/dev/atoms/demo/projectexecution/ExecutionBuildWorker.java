package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceReadGateway;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal one-shot orchestration. No scheduler/HTTP activation, retries, or adoption after a crash. */
final class ExecutionBuildWorker {
    interface Tools {
        OwnedProcess.Result<ExecutionToolProbe.Version> probe(ExecutionToolchain tools, ExecutionToolchain.Kind kind,
                ExecutionToolHome home, ExecutionProcess.Deadline deadline, BooleanSupplier cancelled,
                UUID build, UUID execution, OwnedProcess.Preflight preflight) throws IOException;
        OwnedProcess.Result<ExecutionProcess.Output> command(ExecutionProcess.Invocation invocation,
                ExecutionProcess.Deadline deadline, BooleanSupplier cancelled, UUID build, UUID execution,
                OwnedProcess.Preflight preflight, ExecutionTestDatabase.Launch database) throws IOException;
    }
    private static final Tools NATIVE = new Tools() {
        @Override public OwnedProcess.Result<ExecutionToolProbe.Version> probe(ExecutionToolchain tools, ExecutionToolchain.Kind kind,
                ExecutionToolHome home, ExecutionProcess.Deadline deadline, BooleanSupplier cancelled,
                UUID build, UUID execution, OwnedProcess.Preflight preflight) throws IOException {
            return new ExecutionToolProbe().run(tools, kind, home, deadline, cancelled, build, execution, preflight);
        }
        @Override public OwnedProcess.Result<ExecutionProcess.Output> command(ExecutionProcess.Invocation invocation,
                ExecutionProcess.Deadline deadline, BooleanSupplier cancelled, UUID build, UUID execution,
                OwnedProcess.Preflight preflight, ExecutionTestDatabase.Launch database) throws IOException {
            return new ExecutionProcess().run(invocation, deadline, cancelled, build, execution, preflight, database);
        }
    };
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ExecutionPaths paths;
    private final ExecutionSnapshot snapshots;
    private final ExecutionTestDatabase databases;
    private final List<Path> protectedRoots;
    private final Tools runner;
    private final LongSupplier clock;
    private final ExecutionBuildRepository builds;
    private final ExecutionBuildSettlement settlement;

    ExecutionBuildWorker(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, ExecutionPaths paths, ProjectSourceReadGateway source,
            ExecutionTestDatabase databases, List<Path> protectedRoots) {
        this(settings, projects, jdbc, transactions, paths, source, databases, protectedRoots, NATIVE, System::nanoTime);
    }
    // Controlled-child/clock test seam only. Never exposed through configuration or requests.
    ExecutionBuildWorker(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, ExecutionPaths paths, ProjectSourceReadGateway source,
            ExecutionTestDatabase databases, List<Path> protectedRoots, Tools runner, LongSupplier clock) {
        this.settings = Objects.requireNonNull(settings); this.projects = Objects.requireNonNull(projects);
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.paths = Objects.requireNonNull(paths); this.snapshots = new ExecutionSnapshot(source, paths);
        this.databases = Objects.requireNonNull(databases); this.protectedRoots = List.copyOf(protectedRoots);
        this.runner = Objects.requireNonNull(runner); this.clock = Objects.requireNonNull(clock);
        builds = new ExecutionBuildRepository(jdbc);
        settlement = new ExecutionBuildSettlement(settings, projects, jdbc, transactions);
    }

    /** Caller retains execution-root lease and coordination authority until this synchronous call returns. */
    ExecutionBuild run(UUID owner, UUID project, UUID id, BooleanSupplier cancelled,
                       OwnedProcess.Preflight coordination) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        final ExecutionSettings.Configuration config;
        final ExecutionProcess.Deadline deadline;
        final ExecutionBuild build;
        try {
            config = settings.load(); coordination.check();
            if (!config.allows(owner) || !config.root().equals(paths.checkedRoot())) throw unavailable();
            // Parameterized, scoped read; admission's timeout is immutable. Never extend it on dequeue.
            // https://docs.spring.io/spring-framework/reference/6.2/data-access/jdbc/core.html#jdbc-JdbcTemplate
            int original = jdbc.queryForObject("SELECT task_timeout_seconds FROM execution_builds WHERE id=? AND project_id=? AND owner_id=?",
                    Integer.class, id, project, owner);
            deadline = new ExecutionProcess.Deadline(Duration.ofSeconds(Math.min(original, config.taskTimeout().toSeconds())), clock);
            build = new ExecutionBuildClaim(settings, projects, builds, jdbc, transactions).claim(owner, project, id);
        } catch (IOException | RuntimeException error) { throw unavailable(); }
        try {
            return execute(build, config, deadline, cancelled, coordination);
        } catch (IOException | RuntimeException error) {
            // An uncertain commit is not a failed execution: never retry journal/settlement writes.
            if (error instanceof DataAccessException || "EXECUTION_STATE_UNAVAILABLE".equals(error.getMessage())) throw unavailable();
            final ExecutionBuild current;
            try { current = builds.get(project, id); }
            catch (RuntimeException stateError) { throw unavailable(); }
            if (current.finishedAt() != null || current.status().equals("NEEDS_RECONCILIATION")) return current;
            String code = "EXECUTION_ARTIFACT_INVALID".equals(error.getMessage()) ? error.getMessage() : ExecutionFrontend.failureCode(error);
            return settlement.fail(build, code);
        }
    }

    private ExecutionBuild execute(ExecutionBuild build, ExecutionSettings.Configuration config, ExecutionProcess.Deadline deadline,
            BooleanSupplier cancellation, OwnedProcess.Preflight coordination) throws IOException {
        BooleanSupplier cancelled = () -> cancellation.getAsBoolean() || builds.get(build.projectId(), build.id()).cancelRequested();
        OwnedProcess.Budget budget = () -> {
            if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean()) throw new IOException("EXECUTION_CANCELLED");
            return deadline.remaining();
        };
        OwnedProcess.Preflight control = () -> {
            check(deadline, cancelled); coordination.check();
            if (!config.equals(settings.load()) || !config.root().equals(paths.checkedRoot())
                    || projects.get(build.ownerId(), build.projectId().toString()).archived()) throw new IOException("EXECUTION_PREFLIGHT_FAILED");
            check(deadline, cancelled);
        };
        control.check();
        var task = paths.createBuild(build.id(), budget);
        var snapshot = snapshots.materialize(build.ownerId(), build.projectId(), build.sourceVersionId(), task, budget::remaining);
        if (!snapshot.tree().sha256().equals(build.treeSha256())) throw new IOException("EXECUTION_SOURCE_CHANGED");
        var protectedInputs = new ArrayList<>(protectedRoots); protectedInputs.add(config.root());
        var policy = ExecutionApprovedPolicy.capture(config.policy(), protectedInputs, budget);
        var npm = policy.npm().checkBuild(snapshot.tree()); var maven = policy.maven().check(snapshot.tree());
        var tools = ExecutionToolchain.capture(new ExecutionToolchain.Locations(config.node(), config.npm(), config.java(), config.maven()),
                protectedInputs, budget);
        var home = ExecutionToolHome.create(paths, task, budget);
        var database = databases.verify(build.projectId(), budget);
        OwnedProcess.Preflight inputs = () -> { control.check(); snapshots.verify(snapshot); policy.verify(budget); control.check(); };
        var probes = new ExecutionProbeJournal(settings, projects, jdbc, transactions, build, paths, task, home, tools);
        for (var kind : ExecutionToolchain.Kind.values()) probe(build, tools, kind, home, deadline, cancelled, inputs, probes);
        var journal = new ExecutionBuildJournal(settings, projects, jdbc, transactions, build, paths, task);
        var frontend = new ExecutionFrontend(paths, task, tools, home, inputs,
                (command, time, cancel, taskId, execution, preflight) -> runner.command(command, time, cancel, taskId, execution, preflight, null))
                .run(build.id(), deadline, cancelled, journal);
        var packaging = ExecutionPackaging.assemble(paths, task, budget, inputs);
        OwnedProcess.Preflight packaged = () -> { packaging.verify(budget); home.verify(budget); tools.verify(budget); };
        var javaTests = new ExecutionJava(paths, task, tools, home, packaged,
                (command, time, cancel, taskId, execution, preflight) -> {
                    var current = databases.verify(build.projectId(), budget);
                    if (!database.bindingId().equals(current.bindingId()) || database.databaseOid() != current.databaseOid()
                            || database.roleOid() != current.roleOid()) throw new IOException("EXECUTION_PREFLIGHT_FAILED");
                    var launch = databases.authorizeLaunch(build.projectId(), taskId, execution, paths.project(task).resolve("backend"), budget);
                    return runner.command(command, time, cancel, taskId, execution, preflight, launch);
                }).run(build.id(), deadline, cancelled, journal);
        var identities = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ExecutionToolchain.Kind.values()) identities.put(kind, tools.identity(kind));
        var artifact = ExecutionArtifact.seal(paths, task, build,
                new ExecutionArtifact.Evidence(identities, npm, maven, frontend, javaTests, packaging.summary()), budget, packaged);
        packaged.check(); check(deadline, cancelled);
        return settlement.succeed(build, artifact, budget);
    }

    private void probe(ExecutionBuild build, ExecutionToolchain tools, ExecutionToolchain.Kind kind, ExecutionToolHome home,
            ExecutionProcess.Deadline deadline, BooleanSupplier cancelled, OwnedProcess.Preflight inputs,
            ExecutionProbeJournal journal) throws IOException {
        inputs.check(); check(deadline, cancelled);
        UUID execution = UUID.randomUUID();
        journal.begin(kind, execution);
        OwnedProcess.Stopped stopped = null;
        ExecutionToolProbe.Version version = null;
        String code = null;
        try {
            var result = runner.probe(tools, kind, home, deadline, cancelled, build.id(), execution, inputs);
            stopped = result.stopped(); version = result.summary();
            if (stopped == null || !stopped.matches(build.id(), execution, home.home())) throw new IOException("EXECUTION_PROCESS_UNSETTLED");
            inputs.check(); check(deadline, cancelled);
        } catch (IOException | RuntimeException error) {
            if (error instanceof OwnedProcess.Failure failure) stopped = failure.stopped();
            if (stopped != null && !stopped.matches(build.id(), execution, home.home())) stopped = null;
            code = stopped == null ? "EXECUTION_PROCESS_UNSETTLED" : ExecutionFrontend.failureCode(error);
        }
        journal.finish(kind, execution, code == null ? version : null, stopped, code);
        if (code != null) throw new IOException(code);
    }
    private static void check(ExecutionProcess.Deadline deadline, BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("EXECUTION_CANCELLED");
        deadline.remaining();
    }
    private static IOException unavailable() { return new IOException("EXECUTION_STATE_UNAVAILABLE"); }
}
