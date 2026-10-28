package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One synchronous, owned lock-only task. Scheduling/recovery never reconstruct this worker from an old PID. */
final class DependencyPreparationWorker {
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ExecutionPaths paths;
    private final ProjectSourceReadGateway source;
    private final ProjectSourceEditGateway edits;
    private final DependencyPreparationService service;
    private final DependencyPreparationRepository repository;
    private final List<Path> protectedRoots;

    DependencyPreparationWorker(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, ExecutionPaths paths, ProjectSourceReadGateway source,
            ProjectSourceEditGateway edits, DependencyPreparationService service, List<Path> protectedRoots) {
        this.settings = settings; this.projects = projects; this.jdbc = jdbc; this.transactions = transactions;
        this.paths = paths; this.source = source; this.edits = edits; this.service = service;
        this.protectedRoots = List.copyOf(protectedRoots); repository = new DependencyPreparationRepository(jdbc);
    }

    /** Caller retains the execution-root lease and exclusive coordination authority until this call returns. */
    DependencyPreparationRepository.Task run(UUID owner, UUID project, UUID id, BooleanSupplier cancellation,
                                             OwnedProcess.Preflight coordination) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        final ExecutionSettings.Configuration config;
        final ExecutionProcess.Deadline deadline;
        final ProjectSourceEditGateway.EditHandle edit;
        final DependencyPreparationRepository.Task task;
        try {
            config = settings.load(); coordination.check();
            if (!config.allows(owner) || !config.root().equals(paths.checkedRoot()) || !source.readyForExecution()) throw unavailable();
            edit = Objects.requireNonNull(service.handle(id));
            int original = jdbc.queryForObject("SELECT task_timeout_seconds FROM dependency_preparations WHERE id=? AND project_id=? AND owner_id=?",
                    Integer.class, id, project, owner);
            deadline = new ExecutionProcess.Deadline(Duration.ofSeconds(Math.min(original, config.taskTimeout().toSeconds())));
            task = new DependencyPreparationClaim(settings, projects, repository, new ExecutionBuildRepository(jdbc), edits, jdbc, transactions)
                    .claim(owner, project, id, edit);
        } catch (IOException | RuntimeException error) { throw unavailable(); }
        var lifecycle = new DependencyPreparationLifecycle(settings, projects, jdbc, transactions, repository, service, edits, task, edit);
        try {
            return execute(task, edit, lifecycle, config, deadline, cancellation, coordination);
        } catch (IOException | RuntimeException error) {
            Exception failure = error instanceof UncheckedIOException wrapped ? wrapped.getCause() : error;
            // A database write with an uncertain outcome is not permission to retry settlement.
            if (failure instanceof DataAccessException || "EXECUTION_STATE_UNAVAILABLE".equals(failure.getMessage())) throw unavailable();
            return lifecycle.settle(DependencyPreparationLifecycle.failureCode(failure));
        }
    }

    private DependencyPreparationRepository.Task execute(DependencyPreparationRepository.Task task, ProjectSourceEditGateway.EditHandle edit,
            DependencyPreparationLifecycle lifecycle, ExecutionSettings.Configuration config, ExecutionProcess.Deadline deadline,
            BooleanSupplier cancellation, OwnedProcess.Preflight coordination) throws IOException {
        BooleanSupplier cancelled = () -> cancellation.getAsBoolean() || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT cancel_requested FROM dependency_preparations WHERE id=? AND project_id=?", Boolean.class, task.id(), task.projectId()));
        // File reads share the same deadline, without making a DB round trip for every chunk.
        OwnedProcess.Budget budget = () -> { check(deadline, cancellation); return deadline.remaining(); };
        OwnedProcess.Preflight control = () -> {
            check(deadline, cancelled); coordination.check();
            if (!config.equals(settings.load()) || !config.root().equals(paths.checkedRoot()) || !source.readyForExecution()
                    || projects.get(task.ownerId(), task.projectId().toString()).archived()) throw new IOException("EXECUTION_PREFLIGHT_FAILED");
            check(deadline, cancelled);
        };
        control.check();
        var snapshot = source.read(task.ownerId(), task.projectId(), task.baseVersionId(), budget::remaining);
        if (!snapshot.tree().sha256().equals(task.treeSha256())) throw new IOException("EXECUTION_SOURCE_CHANGED");
        var excluded = new ArrayList<>(protectedRoots); excluded.add(config.root());
        var policy = ExecutionApprovedPolicy.capture(config.policy(), excluded, budget);
        var approved = policy.npm().checkPreparation(snapshot.tree());
        if (!approved.manifestSha256().equals(task.manifestSha256()) || !Objects.equals(approved.baseLockSha256(), task.baseLockSha256())
                || !approved.policySha256().equals(task.policySha256())) throw new IOException("EXECUTION_DEPENDENCY_POLICY_INVALID");
        edits.prepareEdit(edit, budget::remaining);
        Path work = edits.workPath(edit);
        var scratch = paths.createBuild(task.id(), budget);
        var home = ExecutionToolHome.create(paths, scratch, budget);
        var tools = ExecutionToolchain.capture(new ExecutionToolchain.Locations(config.node(), config.npm(), config.java(), config.maven()), excluded, budget);
        var invocation = tools.lockPreparation(home, work);
        OwnedProcess.Preflight inputs = () -> {
            control.check();
            if (!work.equals(edits.workPath(edit)) || !snapshot.tree().sha256().equals(edits.readEdit(edit).sha256()))
                throw new IOException("EXECUTION_SOURCE_CHANGED");
            home.verify(budget); tools.verify(budget); policy.verify(budget); control.check();
        };
        inputs.check();
        UUID execution = UUID.randomUUID();
        lifecycle.begin(execution);
        OwnedProcess.Stopped stopped = null;
        Exception failure = null;
        try {
            stopped = new ExecutionProcess().run(invocation, deadline, cancelled, task.id(), execution, inputs).stopped();
        } catch (IOException | RuntimeException error) {
            if (error instanceof OwnedProcess.Failure owned) stopped = owned.stopped();
            failure = error;
        }
        if (stopped != null && !stopped.matches(task.id(), execution, work)) stopped = null;
        lifecycle.stopped(stopped);
        if (stopped == null) return lifecycle.settle("EXECUTION_PROCESS_UNSETTLED");
        if (failure != null) return lifecycle.settle(DependencyPreparationLifecycle.failureCode(failure));
        control.check(); home.verify(budget); tools.verify(budget); policy.verify(budget);
        lifecycle.accepting();
        var accepted = edits.sealAndAccept(edit, "dependency-preparation-" + task.id(), candidate -> {
            try {
                control.check(); policy.verify(budget);
                policy.npm().checkPrepared(snapshot.tree(), candidate);
            } catch (IOException error) { throw new UncheckedIOException(error); }
        });
        if (accepted instanceof ProjectSourceEditGateway.Submitted submitted) {
            lifecycle.bindSource();
            try { submitted.completion().get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS); }
            catch (TimeoutException error) { return lifecycle.settle("EXECUTION_TIMEOUT"); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt(); return lifecycle.settle("EXECUTION_CANCELLED");
            } catch (ExecutionException error) { return lifecycle.settle("EXECUTION_SOURCE_UNSETTLED"); }
        }
        return lifecycle.settle(null);
    }

    private static void check(ExecutionProcess.Deadline deadline, BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("EXECUTION_CANCELLED");
        deadline.remaining();
    }
    private static IOException unavailable() { return new IOException("EXECUTION_STATE_UNAVAILABLE"); }
}
