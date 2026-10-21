package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionJavaTest {
    @TempDir Path temporary;

    @Test void runsFixedMavenWithSharedBudgetAndCommitsIntentBeforeLaunchAndProofBeforeCompletion() throws Exception {
        try (var fixture = new Harness("success")) {
            assertThat(fixture.run()).isEqualTo(new ExecutionJavaReport.Summary(1, 1));
            assertThat(fixture.events).containsExactly("begin", "run", "finish");
            var stage = fixture.finished.get(0);
            assertThat(stage.code()).isNull();
            assertThat(stage.stopped().matches(fixture.id, stage.execution(), fixture.backend)).isTrue();
            assertThat(stage.tests()).isEqualTo(new ExecutionJavaReport.Summary(1, 1));
            assertThat(stage.output().stdoutBytes()).isPositive();
            assertThat(stage.toString()).doesNotContain("CANARY", fixture.backend.toString());
            assertThat(fixture.preflights).isGreaterThanOrEqualTo(3);
            assertThatThrownBy(fixture::run).hasMessage("EXECUTION_PIPELINE_REUSED");
        }
    }

    @Test void reportAndProcessFailuresAreNotSuccessfulEvenIfAnAppJarExists() throws Exception {
        for (String mode : List.of("exit", "empty", "skipped")) {
            try (var fixture = new Harness(mode)) {
                assertThatThrownBy(fixture::run).hasMessage(mode.equals("exit")
                        ? "EXECUTION_PROCESS_FAILED" : "EXECUTION_REPORT_INVALID").hasNoCause();
                assertThat(fixture.finished).hasSize(1);
                assertThat(fixture.finished.get(0).stopped()).isNotNull();
                assertThat(fixture.finished.get(0).tests()).isNull();
                assertThat(fixture.backend.resolve("target/app.jar")).exists();
            }
        }
    }

    @Test void unknownOrMismatchedStopNeverAllowsReadingReports() throws Exception {
        for (String mode : List.of("unsettled", "wrong-build", "wrong-execution", "wrong-cwd")) {
            try (var fixture = new Harness(mode)) {
                assertThatThrownBy(fixture::run).hasMessage("EXECUTION_PROCESS_UNSETTLED");
                // A denied process-enumeration check must not masquerade as this mismatch scenario.
                assertThat(fixture.completed).as(mode).isEqualTo(!mode.equals("unsettled"));
                var stage = fixture.finished.get(0);
                assertThat(stage.stopped()).isNull();
                assertThat(stage.tests()).isNull();
            }
        }
    }

    @Test void failedPersistenceIsNotRetriedAndFailedIntentDoesNotLaunch() throws Exception {
        for (String mode : List.of("begin-fails", "finish-fails")) {
            try (var fixture = new Harness(mode)) {
                assertThatThrownBy(fixture::run).hasMessage("EXECUTION_STATE_UNAVAILABLE").hasNoCause();
                assertThat(fixture.events).containsExactlyElementsOf(mode.equals("begin-fails")
                        ? List.of("begin") : List.of("begin", "run", "finish"));
            }
        }
    }

    @Test void checksCancellationAndDeadlineAfterProcessStops() throws Exception {
        for (String mode : List.of("cancel-after-exit", "expire-after-exit", "source-drift")) {
            try (var fixture = new Harness(mode)) {
                assertThatThrownBy(fixture::run).hasMessage(switch (mode) {
                    case "cancel-after-exit" -> "EXECUTION_CANCELLED";
                    case "expire-after-exit" -> "EXECUTION_TIMEOUT";
                    default -> "EXECUTION_SOURCE_CHANGED";
                });
                assertThat(fixture.finished.get(0).stopped()).isNotNull();
                assertThat(fixture.finished.get(0).tests()).isNull();
            }
        }
    }

    @Test void refusesCancelledPreflightOrExistingOutputsBeforeIntent() throws Exception {
        for (String mode : List.of("cancel-before", "preflight-fails", "old-output")) {
            try (var fixture = new Harness(mode)) {
                if (mode.equals("cancel-before")) fixture.cancel.set(true);
                if (mode.equals("old-output")) Files.createDirectory(fixture.backend.resolve("target"));
                assertThatThrownBy(fixture::run).hasMessage(switch (mode) {
                    case "cancel-before" -> "EXECUTION_CANCELLED";
                    case "old-output" -> "EXECUTION_OUTPUT_NOT_CLEAN";
                    default -> "EXECUTION_CHECK_FAILED";
                }).hasNoCause();
                assertThat(fixture.events).isEmpty();
            }
        }
    }

    final class Harness implements AutoCloseable, ExecutionJava.Journal {
        final UUID id = UUID.randomUUID();
        final AtomicBoolean cancel = new AtomicBoolean();
        final AtomicLong clock = new AtomicLong();
        final ExecutionProcess.Deadline deadline = new ExecutionProcess.Deadline(Duration.ofSeconds(30), clock::get);
        final List<String> events = new ArrayList<>();
        final List<ExecutionJava.Stage> finished = new ArrayList<>();
        final ExecutionPaths paths;
        final Path backend;
        final ExecutionJava pipeline;
        final String mode;
        boolean completed;
        int preflights;
        Harness(String mode) throws Exception {
            this.mode = mode;
            Path root = Files.createDirectory(temporary.toRealPath().resolve(id.toString()));
            var tools = ExecutionToolchain.capture(ExecutionToolchainTest.installation(root), List.of(), deadline);
            paths = new ExecutionPaths(root.resolve("execution"), List.of());
            var task = paths.createBuild(id);
            var home = ExecutionToolHome.create(paths, task, deadline);
            backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
            pipeline = new ExecutionJava(paths, task, tools, home, () -> {
                preflights++;
                if (mode.equals("preflight-fails")) throw new IOException("PRIVATE_CANARY");
                if (mode.equals("source-drift") && completed) throw new IOException("EXECUTION_SOURCE_CHANGED");
            }, (invocation, shared, cancelled, build, execution, preflight) -> {
                assertThat(events).containsExactly("begin"); events.add("run");
                assertThat(shared).isSameAs(deadline);
                assertThat(invocation.builder().directory().toPath()).isEqualTo(backend);
                assertThat(invocation.builder().command()).endsWith("-Pfull-project-package", "verify")
                        .doesNotContain("sh", "-c", "mvnw", "-DskipTests", "-Dmaven.test.skip=true");
                if (mode.equals("unsettled")) return new OwnedProcess.Result<>(new ExecutionProcess.Output(1, 1), null);
                Path java = Path.of(System.getProperty("java.home"));
                var child = new ExecutionProcess.Invocation(java.resolve("bin/java"),
                        List.of("-cp", Path.of("target/test-classes").toAbsolutePath().toString(), Fixture.class.getName(), mode),
                        mode.equals("wrong-cwd") ? home.home() : backend, home.home(), home.temporary(), java, java.resolve("bin"));
                var result = new ExecutionProcess().run(child, shared, cancelled,
                        mode.equals("wrong-build") ? UUID.randomUUID() : build,
                        mode.equals("wrong-execution") ? UUID.randomUUID() : execution, preflight);
                completed = true;
                if (mode.equals("cancel-after-exit")) cancel.set(true);
                if (mode.equals("expire-after-exit")) clock.set(Duration.ofSeconds(30).toNanos());
                return result;
            });
        }
        ExecutionJavaReport.Summary run() throws IOException { return pipeline.run(id, deadline, cancel::get, this); }
        @Override public void begin(UUID execution) throws IOException {
            events.add("begin");
            if (mode.equals("begin-fails")) throw new IOException("PRIVATE_DB_CANARY");
        }
        @Override public void finish(ExecutionJava.Stage stage) throws IOException {
            events.add("finish");
            if (mode.equals("finish-fails")) throw new IOException("PRIVATE_DB_CANARY");
            finished.add(stage);
        }
        @Override public void close() throws IOException { paths.close(); }
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            System.out.print("PRIVATE_OUTPUT_CANARY");
            System.err.print("PRIVATE_STDERR_CANARY");
            if (args[0].equals("wrong-cwd")) return;
            Files.writeString(Path.of("target/app.jar"), "opaque fixture bytes, not an executable JAR");
            if (args[0].equals("exit")) System.exit(4);
            if (args[0].equals("empty")) return;
            String xml = ExecutionJavaReportsTest.xml();
            if (args[0].equals("skipped")) xml = xml.replace("<testcase name=\"works\"/>",
                    "<testcase name=\"works\"><skipped/></testcase>");
            Files.writeString(Path.of("target/surefire-reports/TEST-Example.xml"), xml);
        }
    }
}
