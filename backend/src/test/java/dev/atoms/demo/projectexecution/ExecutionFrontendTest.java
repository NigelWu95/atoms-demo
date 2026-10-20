package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;
import static org.assertj.core.api.Assertions.*;

class ExecutionFrontendTest {
    @TempDir Path temporary;

    @Test void runsOnlyFixedOperationsWithOneDeadlineAndProofBeforeEachCompletion() throws Exception {
        try (var fixture = new Harness("success")) {
            var summary = fixture.run();
            assertThat(summary.tests()).isEqualTo(1);
            assertThat(fixture.operations).containsExactly(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD);
            assertThat(fixture.events).containsExactly("begin:NPM_CI", "end:NPM_CI", "begin:TYPECHECK", "end:TYPECHECK",
                    "begin:LINT", "end:LINT", "begin:UNIT_TEST", "end:UNIT_TEST", "begin:FRONTEND_BUILD", "end:FRONTEND_BUILD");
            assertThat(fixture.finished).allSatisfy(stage -> {
                assertThat(stage.code()).isNull();
                assertThat(stage.stopped().matches(fixture.id, stage.execution(), fixture.frontend)).isTrue();
                assertThat(stage.output().stdoutBytes()).isPositive();
                assertThat(stage.toString()).doesNotContain("CANARY", fixture.frontend.toString());
            });
            assertThat(fixture.preflights).isGreaterThanOrEqualTo(15);
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_PIPELINE_REUSED");
        }
    }

    @Test void failureStopsAtTheFailingStageAndDoesNotRunBuildAfterInvalidReport() throws Exception {
        for (String mode : List.of("exit", "empty", "skipped", "hardlink", "symlink", "replace", "oversized")) {
            try (var fixture = new Harness(mode)) {
                assertThatThrownBy(fixture::run).hasMessage(mode.equals("exit")
                        ? "EXECUTION_PROCESS_FAILED" : "EXECUTION_REPORT_INVALID").hasNoCause();
                assertThat(fixture.operations).doesNotContain(FRONTEND_BUILD);
                assertThat(fixture.finished.get(fixture.finished.size() - 1).stopped()).isNotNull();
                assertThat(fixture.finished.toString()).doesNotContain("CANARY");
            }
        }
    }

    @Test void oldOutputsAreRejectedWithoutDeletingOrLaunchingAnything() throws Exception {
        for (String output : List.of("node_modules", "dist", "coverage", "test-results")) {
            try (var fixture = new Harness("success")) {
                Path old = Files.createDirectory(fixture.frontend.resolve(output));
                Files.writeString(old.resolve("evidence"), "preserve");
                assertThatThrownBy(fixture::run).hasMessage("EXECUTION_OUTPUT_NOT_CLEAN");
                assertThat(fixture.operations).isEmpty();
                assertThat(Files.readString(old.resolve("evidence"))).isEqualTo("preserve");
            }
        }
    }

    @Test void cancellationAndDeadlineAreSharedAcrossStagesAndCheckedAfterExit() throws Exception {
        try (var fixture = new Harness("cancel-after-install")) {
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_CANCELLED");
            assertThat(fixture.operations).containsExactly(NPM_CI);
            assertThat(fixture.finished.get(0).stopped()).isNotNull();
        }
        try (var fixture = new Harness("expire-after-install")) {
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_TIMEOUT");
            assertThat(fixture.operations).containsExactly(NPM_CI);
            assertThat(fixture.finished.get(0).stopped()).isNotNull();
        }
        try (var fixture = new Harness("success")) {
            fixture.cancel.set(true);
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_CANCELLED");
            assertThat(fixture.events).isEmpty();
        }
    }

    @Test void journalFailurePreventsLaunchOrTheNextStageAndUnknownStopIsNotSuccess() throws Exception {
        for (String mode : List.of("begin-fails", "finish-fails", "unsettled")) {
            try (var fixture = new Harness(mode)) {
                assertThatThrownBy(fixture::run).hasMessage(mode.equals("unsettled")
                        ? "EXECUTION_PROCESS_UNSETTLED" : "EXECUTION_STATE_UNAVAILABLE").hasNoCause();
                assertThat(fixture.operations).hasSize(mode.equals("begin-fails") ? 0 : 1);
                if (mode.equals("unsettled")) assertThat(fixture.finished.get(0).stopped()).isNull();
            }
        }
    }

    @Test void sourcePolicyFailureOrToolHomeDriftCannotBeHiddenBySuccessfulExit() throws Exception {
        try (var fixture = new Harness("preflight-canary")) {
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_CHECK_FAILED").hasNoCause();
            assertThat(fixture.operations).isEmpty();
        }
        try (var fixture = new Harness("source-drift")) {
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_SOURCE_CHANGED");
            assertThat(fixture.operations).containsExactly(NPM_CI);
        }
        try (var fixture = new Harness("success")) {
            Files.writeString(fixture.home.home().resolve("npmrc"), "CANARY");
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_STORAGE_UNAVAILABLE");
            assertThat(fixture.operations).isEmpty();
        }
    }

    final class Harness implements AutoCloseable, ExecutionFrontend.Journal {
        final UUID id = UUID.randomUUID();
        final AtomicBoolean cancel = new AtomicBoolean();
        final AtomicLong clock = new AtomicLong();
        final ExecutionProcess.Deadline deadline = new ExecutionProcess.Deadline(Duration.ofSeconds(30), clock::get);
        final List<ExecutionToolchain.Operation> operations = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<ExecutionFrontend.Stage> finished = new ArrayList<>();
        final ExecutionPaths paths;
        final ExecutionToolHome home;
        final Path frontend;
        final ExecutionFrontend pipeline;
        final String mode;
        int preflights;
        boolean processCompleted;

        Harness(String mode) throws Exception {
            this.mode = mode;
            Path root = Files.createDirectory(temporary.toRealPath().resolve(id.toString()));
            var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(root), List.of(), deadline);
            paths = new ExecutionPaths(root.resolve("execution"), List.of());
            var task = paths.createBuild(id);
            home = ExecutionToolHome.create(paths, task, deadline);
            frontend = ExecutionRootLease.directory(paths.project(task).resolve("frontend"));
            pipeline = new ExecutionFrontend(paths, task, tools, home, () -> {
                preflights++;
                if (mode.equals("preflight-canary")) throw new IOException("PRIVATE_SOURCE_CANARY");
                if (mode.equals("source-drift") && processCompleted) throw new IOException("EXECUTION_SOURCE_CHANGED");
            }, (invocation, shared, cancelled, build, execution, preflight) -> {
                assertThat(shared).isSameAs(deadline);
                assertThat(cancelled.getAsBoolean()).isEqualTo(cancel.get());
                var op = List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD).get(operations.size());
                assertThat(events.get(events.size() - 1)).isEqualTo("begin:" + op);
                var command = invocation.builder().command();
                assertThat(command).doesNotContain("sh", "-c", "npm", "mvnw", "build.mjs", "test:unit");
                if (op == NPM_CI) assertThat(command).endsWith("ci", "--ignore-scripts", "--no-audit", "--no-fund");
                if (op == UNIT_TEST) assertThat(command).endsWith("run", "--reporter=json", "--outputFile=test-results/unit.json");
                operations.add(op);
                if (mode.equals("unsettled")) return new OwnedProcess.Result<>(new ExecutionProcess.Output(1, 1), null);
                Path java = Path.of(System.getProperty("java.home"));
                var child = new ExecutionProcess.Invocation(java.resolve("bin/java"),
                        List.of("-cp", Path.of("target/test-classes").toAbsolutePath().toString(), Fixture.class.getName(),
                                mode.equals("exit") ? "exit" : op == UNIT_TEST ? mode : "noop"),
                        frontend, home.home(), home.temporary(), java, java.resolve("bin"));
                var result = new ExecutionProcess().run(child, shared, cancelled, build, execution, preflight);
                processCompleted = true;
                if (mode.equals("cancel-after-install")) cancel.set(true);
                if (mode.equals("expire-after-install")) clock.set(Duration.ofSeconds(30).toNanos());
                return result;
            });
        }
        ExecutionDiagnostics.Frontend run() throws IOException { return pipeline.run(id, deadline, cancel::get, this); }
        @Override public void begin(ExecutionToolchain.Operation operation, UUID execution) throws IOException {
            if (mode.equals("begin-fails")) throw new IOException("PRIVATE_DB_CANARY");
            events.add("begin:" + operation);
        }
        @Override public void finish(ExecutionFrontend.Stage stage) throws IOException {
            if (mode.equals("finish-fails")) throw new IOException("PRIVATE_DB_CANARY");
            events.add("end:" + stage.operation()); finished.add(stage);
        }
        @Override public void close() throws IOException { paths.close(); }
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            System.out.print("PRIVATE_OUTPUT_CANARY");
            System.err.print("PRIVATE_STDERR_CANARY");
            if (args[0].equals("exit")) System.exit(4);
            if (args[0].equals("noop") || args[0].equals("empty")) return;
            Path report = Path.of("test-results/unit.json");
            if (args[0].equals("replace") || args[0].equals("symlink")) {
                Files.move(report, report.resolveSibling("old.json"));
                if (args[0].equals("symlink")) Files.createSymbolicLink(report, Path.of("old.json"));
                else Files.createFile(report, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            }
            if (args[0].equals("hardlink")) Files.createLink(report.resolveSibling("alias"), report);
            String json = ExecutionDiagnosticsTest.report();
            if (args[0].equals("skipped")) json = json.replace("\"status\":\"passed\",\"title\"", "\"status\":\"skipped\",\"title\"");
            Files.writeString(report, args[0].equals("oversized") ? "x".repeat((1 << 20) + 1) : json);
        }
    }
}
