package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;

/** Single-use frontend phase, not a scheduler or build-success publisher. No npm scripts or shell. */
final class ExecutionFrontend {
    private static final List<ExecutionToolchain.Operation> ORDER = List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD);
    interface Journal {
        /** Must commit the one-shot intent before returning; failure prevents launch. */
        void begin(ExecutionToolchain.Operation operation, UUID execution) throws IOException;
        /** Must verify matching stop evidence; failure leaves the persisted intent unresolved. */
        void finish(Stage stage) throws IOException;
    }
    record Stage(ExecutionToolchain.Operation operation, UUID execution, String code, OwnedProcess.Stopped stopped,
                 ExecutionProcess.Output output, ExecutionDiagnostics.Frontend tests) {}
    @FunctionalInterface interface Runner {
        OwnedProcess.Result<ExecutionProcess.Output> run(ExecutionProcess.Invocation invocation,
                ExecutionProcess.Deadline deadline, BooleanSupplier cancelled, UUID build, UUID execution,
                OwnedProcess.Preflight preflight) throws IOException;
    }
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final ExecutionToolchain tools;
    private final ExecutionToolHome home;
    private final OwnedProcess.Preflight sourceAndPolicy;
    private final Runner runner;
    private boolean used;

    /** Caller supplies current authorization, immutable-snapshot and approved-policy revalidation. */
    ExecutionFrontend(ExecutionPaths paths, ExecutionPaths.Task task, ExecutionToolchain tools,
                      ExecutionToolHome home, OwnedProcess.Preflight sourceAndPolicy) {
        this(paths, task, tools, home, sourceAndPolicy, new ExecutionProcess()::run);
    }
    // Package-local deterministic execution seam; never selected by configuration or HTTP input.
    ExecutionFrontend(ExecutionPaths paths, ExecutionPaths.Task task, ExecutionToolchain tools,
                      ExecutionToolHome home, OwnedProcess.Preflight sourceAndPolicy, Runner runner) {
        this.paths = Objects.requireNonNull(paths); this.task = Objects.requireNonNull(task);
        this.tools = Objects.requireNonNull(tools); this.home = Objects.requireNonNull(home);
        this.sourceAndPolicy = Objects.requireNonNull(sourceAndPolicy); this.runner = Objects.requireNonNull(runner);
    }

    ExecutionDiagnostics.Frontend run(UUID build, ExecutionProcess.Deadline deadline,
                                     BooleanSupplier cancelled, Journal journal) throws IOException {
        try { return execute(build, deadline, cancelled, journal); }
        catch (IOException | RuntimeException error) { throw new IOException(failureCode(error)); }
    }

    private ExecutionDiagnostics.Frontend execute(UUID build, ExecutionProcess.Deadline deadline,
                                                 BooleanSupplier cancelled, Journal journal) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        synchronized (this) {
            if (used) throw new IOException("EXECUTION_PIPELINE_REUSED");
            used = true;
        }
        check(deadline, cancelled);
        if (!paths.directory(task).getFileName().toString().equals(build.toString())
                || !home.project().equals(paths.project(task))) throw new IOException("EXECUTION_STORAGE_UNAVAILABLE");
        Path frontend = paths.project(task).resolve("frontend");
        paths.privateDirectoryKey(task, frontend);
        for (String name : List.of("node_modules", "dist", "coverage", "test-results")) absent(frontend.resolve(name));
        ExecutionDiagnostics.Frontend tests = null;
        for (var operation : ORDER) {
            preflight(deadline, cancelled);
            Report report = operation == UNIT_TEST ? prepareReport(frontend) : null;
            if (operation == FRONTEND_BUILD) absent(frontend.resolve("dist"));
            UUID execution = UUID.randomUUID();
            try { journal.begin(operation, execution); }
            catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_STATE_UNAVAILABLE"); }
            OwnedProcess.Stopped stopped = null;
            ExecutionProcess.Output output = null;
            String code = null;
            try {
                var result = runner.run(tools.command(operation, home), deadline, cancelled, build, execution,
                        () -> preflight(deadline, cancelled));
                stopped = result.stopped();
                if (stopped == null || !stopped.matches(build, execution, frontend))
                    throw new IOException("EXECUTION_PROCESS_UNSETTLED");
                output = result.summary();
                if (output == null) throw new IOException("EXECUTION_CHECK_FAILED");
                preflight(deadline, cancelled);
                if (report != null) tests = readReport(report, deadline, cancelled);
                check(deadline, cancelled);
            } catch (IOException | RuntimeException error) {
                if (error instanceof OwnedProcess.Failure failure) stopped = failure.stopped();
                if (stopped != null && !stopped.matches(build, execution, frontend)) stopped = null;
                code = stopped == null ? "EXECUTION_PROCESS_UNSETTLED" : failureCode(error);
            }
            // Do not catch a failed persistence call as a second completion. The original intent stays pending.
            try { journal.finish(new Stage(operation, execution, code, stopped, output, operation == UNIT_TEST ? tests : null)); }
            catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_STATE_UNAVAILABLE"); }
            if (code != null) throw new IOException(code);
        }
        return tests;
    }

    private void preflight(ExecutionProcess.Deadline deadline, BooleanSupplier cancelled) throws IOException {
        check(deadline, cancelled);
        sourceAndPolicy.check();
        home.verify(deadline);
        tools.verify(deadline);
        check(deadline, cancelled);
    }
    private record Report(Path directory, Object directoryKey, Path file, Object fileKey) {}
    private Report prepareReport(Path frontend) throws IOException {
        try {
            Path directory = frontend.resolve("test-results");
            absent(directory);
            ExecutionRootLease.directory(directory);
            Object key = paths.privateDirectoryKey(task, directory);
            Path file = directory.resolve("unit.json");
            // Empty, new and private before Vitest starts. Vitest writeFile truncates this inode.
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            return new Report(directory, key, file, paths.privateFileKey(task, file, 0));
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_REPORT_INVALID"); }
    }
    private ExecutionDiagnostics.Frontend readReport(Report report, ExecutionProcess.Deadline deadline,
                                                    BooleanSupplier cancelled) throws IOException {
        check(deadline, cancelled);
        try {
            long size = Files.readAttributes(report.file(), java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).size();
            if (size <= 0 || size > ExecutionDiagnostics.REPORT_MAX_BYTES) throw new IOException();
            verifyReport(report, size);
            byte[] bytes;
            try (var input = Files.newInputStream(report.file(), LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(ExecutionDiagnostics.REPORT_MAX_BYTES + 1);
            }
            if (bytes.length != size) throw new IOException();
            verifyReport(report, size);
            var result = ExecutionDiagnostics.frontend(bytes);
            check(deadline, cancelled);
            return result;
        } catch (IOException | RuntimeException error) {
            if (Set.of("EXECUTION_TIMEOUT", "EXECUTION_CANCELLED").contains(Objects.toString(error.getMessage(), "")))
                throw new IOException(error.getMessage());
            throw new IOException("EXECUTION_REPORT_INVALID");
        }
    }
    private void verifyReport(Report report, long size) throws IOException {
        if (!report.directoryKey().equals(paths.privateDirectoryKey(task, report.directory()))
                || !report.fileKey().equals(paths.privateFileKey(task, report.file(), size))) throw new IOException();
    }
    private static void absent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("EXECUTION_OUTPUT_NOT_CLEAN");
    }
    private static void check(ExecutionProcess.Deadline deadline, BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("EXECUTION_CANCELLED");
        deadline.remaining();
    }
    static String failureCode(Exception error) {
        return switch (Objects.toString(error.getMessage(), "")) {
            case "EXECUTION_CANCELLED", "EXECUTION_TIMEOUT", "EXECUTION_REPORT_INVALID", "EXECUTION_OUTPUT_LIMIT",
                 "EXECUTION_PROCESS_FAILED", "EXECUTION_CHILD_PROCESS_REMAINS", "EXECUTION_PROCESS_UNSETTLED",
                 "EXECUTION_START_FAILED", "EXECUTION_PREFLIGHT_FAILED", "EXECUTION_IO_FAILED",
                 "EXECUTION_SOURCE_CHANGED", "EXECUTION_STORAGE_UNAVAILABLE", "EXECUTION_STORAGE_CAPACITY",
                 "EXECUTION_TOOL_CHANGED", "EXECUTION_STATE_UNAVAILABLE", "EXECUTION_OUTPUT_NOT_CLEAN",
                 "EXECUTION_PIPELINE_REUSED", "EXECUTION_TRANSACTION_ACTIVE" -> error.getMessage();
            default -> "EXECUTION_CHECK_FAILED";
        };
    }
}
