package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Single-use fixed Maven verify stage; neither artifact sealing nor build-success publication. */
final class ExecutionJava {
    interface Journal {
        /** Commit the one-shot MAVEN_PACKAGE intent before returning; failure prevents launch. */
        void begin(UUID execution) throws IOException;
        /** Verify matching backend stop evidence. Failed persistence must leave intent unresolved. */
        void finish(Stage stage) throws IOException;
    }
    record Stage(UUID execution, String code, OwnedProcess.Stopped stopped,
                 ExecutionProcess.Output output, ExecutionJavaReport.Summary tests) {}
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final ExecutionToolchain tools;
    private final ExecutionToolHome home;
    private final OwnedProcess.Preflight inputs;
    private final ExecutionFrontend.Runner runner;
    private boolean used;

    /** Production entry: fixed Maven stage always requires the project's private test database. */
    ExecutionJava(ExecutionPaths paths, ExecutionPaths.Task task, ExecutionToolchain tools,
                  ExecutionToolHome home, OwnedProcess.Preflight inputs, ExecutionTestDatabase databases, UUID project) {
        this(paths, task, tools, home, inputs, (invocation, deadline, cancelled, build, execution, preflight) ->
                new ExecutionProcess().run(invocation, deadline, cancelled, build, execution, preflight,
                        databases.authorizeLaunch(project, build, execution, paths.project(task).resolve("backend"), deadline)));
        Objects.requireNonNull(databases); Objects.requireNonNull(project);
    }
    // Deterministic test seam only, never configurable by users or maintenance settings.
    ExecutionJava(ExecutionPaths paths, ExecutionPaths.Task task, ExecutionToolchain tools,
                  ExecutionToolHome home, OwnedProcess.Preflight inputs, ExecutionFrontend.Runner runner) {
        this.paths = Objects.requireNonNull(paths); this.task = Objects.requireNonNull(task);
        this.tools = Objects.requireNonNull(tools); this.home = Objects.requireNonNull(home);
        this.inputs = Objects.requireNonNull(inputs); this.runner = Objects.requireNonNull(runner);
    }

    ExecutionJavaReport.Summary run(UUID build, ExecutionProcess.Deadline deadline,
                                   BooleanSupplier cancelled, Journal journal) throws IOException {
        try { return execute(build, deadline, cancelled, journal); }
        catch (IOException | RuntimeException error) { throw new IOException(ExecutionFrontend.failureCode(error)); }
    }

    private ExecutionJavaReport.Summary execute(UUID build, ExecutionProcess.Deadline deadline,
                                                BooleanSupplier cancelled, Journal journal) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        synchronized (this) {
            if (used) throw new IOException("EXECUTION_PIPELINE_REUSED");
            used = true;
        }
        check(deadline, cancelled);
        if (!paths.directory(task).getFileName().toString().equals(build.toString())
                || !home.project().equals(paths.project(task))) throw new IOException("EXECUTION_STORAGE_UNAVAILABLE");
        Path backend = paths.project(task).resolve("backend");
        preflight(deadline, cancelled);
        OwnedProcess.Budget budget = () -> { check(deadline, cancelled); return deadline.remaining(); };
        var reports = ExecutionJavaReports.prepare(paths, task, budget);
        UUID execution = UUID.randomUUID();
        try { journal.begin(execution); }
        catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_STATE_UNAVAILABLE"); }
        OwnedProcess.Stopped stopped = null;
        ExecutionProcess.Output output = null;
        ExecutionJavaReport.Summary tests = null;
        String code = null;
        try {
            var result = runner.run(tools.command(ExecutionToolchain.Operation.MAVEN_PACKAGE, home), deadline,
                    cancelled, build, execution, () -> preflight(deadline, cancelled));
            stopped = result.stopped();
            if (stopped == null || !stopped.matches(build, execution, backend))
                throw new IOException("EXECUTION_PROCESS_UNSETTLED");
            output = result.summary();
            if (output == null) throw new IOException("EXECUTION_CHECK_FAILED");
            preflight(deadline, cancelled);
            tests = reports.read(budget);
            preflight(deadline, cancelled);
        } catch (IOException | RuntimeException error) {
            if (error instanceof OwnedProcess.Failure failure) stopped = failure.stopped();
            if (stopped != null && !stopped.matches(build, execution, backend)) stopped = null;
            code = stopped == null ? "EXECUTION_PROCESS_UNSETTLED" : ExecutionFrontend.failureCode(error);
            tests = null;
        }
        // A failed finish is not a second stage failure: retain the original pending intent for reconciliation.
        try { journal.finish(new Stage(execution, code, stopped, output, tests)); }
        catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_STATE_UNAVAILABLE"); }
        if (code != null) throw new IOException(code);
        return tests;
    }

    private void preflight(ExecutionProcess.Deadline deadline, BooleanSupplier cancelled) throws IOException {
        check(deadline, cancelled);
        inputs.check(); home.verify(deadline); tools.verify(deadline);
        check(deadline, cancelled);
    }
    private static void check(ExecutionProcess.Deadline deadline, BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("EXECUTION_CANCELLED");
        deadline.remaining();
    }
}
