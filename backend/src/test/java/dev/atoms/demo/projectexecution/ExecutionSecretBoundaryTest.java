package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.execution.OwnedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

/** Synthetic secrets only. No model, email or ambient credential is needed. */
@ExtendWith(OutputCaptureExtension.class)
class ExecutionSecretBoundaryTest {
    private static final String CANARY = "EX28_PRIVATE_BOUNDARY_CANARY";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void actualChildOutputAndFailureNeverEscapeAsDiagnosticsOrLogs(CapturedOutput captured) throws Exception {
        Path java = Path.of(System.getProperty("java.home"));
        for (String mode : List.of("success", "failure")) {
            UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
            var invocation = new ExecutionProcess.Invocation(java.resolve("bin/java"), List.of("-cp",
                    Path.of("target/test-classes").toAbsolutePath().toString(), Child.class.getName(), mode),
                    temporary, temporary, temporary, java, java.resolve("bin"));
            if (mode.equals("success")) {
                var result = new ExecutionProcess().run(invocation, budget(), () -> false, task, execution, () -> {});
                assertThat(result.summary().stdoutBytes()).isEqualTo(CANARY.length());
                assertThat(result.summary().stderrBytes()).isEqualTo(CANARY.length());
                assertThat(result.stopped().matches(task, execution, temporary)).isTrue();
                assertThat(JSON.writeValueAsString(result.summary())).doesNotContain(CANARY);
            } else {
                assertThatThrownBy(() -> new ExecutionProcess().run(invocation, budget(), () -> false, task, execution, () -> {}))
                        .isInstanceOfSatisfying(OwnedProcess.Failure.class, failure -> {
                            assertThat(failure).hasMessage("EXECUTION_PROCESS_FAILED").hasNoCause();
                            assertThat(failure.getSuppressed()).isEmpty();
                            assertThat(failure.stopped().matches(task, execution, temporary)).isTrue();
                        });
            }
        }
        assertThat(captured.getAll()).doesNotContain(CANARY);
    }

    @Test void everyUntrustedEventFieldSerializesToAllowlistedDataOrAnUnavailableDiagnostic() throws Exception {
        Map<String, Object> valid = Map.of("operation", "UNIT_TEST", "phase", "completed",
                "executionId", UUID.randomUUID().toString(), "tests", 1);
        for (String field : List.of("status", "operation", "phase", "probe", "executionId", "code", "tests",
                "cancelRequested", "stopRequested", "executionPending", "stderr", "command", "source", "path", "password")) {
            var raw = new LinkedHashMap<>(valid); raw.put(field, CANARY);
            var detail = ExecutionDiagnostics.event(JSON.writeValueAsString(raw));
            assertThat(JSON.writeValueAsString(detail)).as(field).doesNotContain(CANARY);
            assertThat(detail.type()).isIn("OPERATION", "UNAVAILABLE");
        }
    }

    @Test void privateReportTextIsReducedToCountsBeforeItCanBecomeACombinedDiagnostic() throws Exception {
        var frontend = ExecutionDiagnostics.frontend(ExecutionDiagnosticsTest.report().replace("CANARY", CANARY).getBytes(StandardCharsets.UTF_8));
        try (var paths = new ExecutionPaths(temporary.toRealPath().resolve("execution"), List.of())) {
            var task = paths.createBuild(UUID.randomUUID());
            Path backend = ExecutionRootLease.directory(paths.project(task).resolve("backend"));
            var reports = ExecutionJavaReports.prepare(paths, task, budget());
            Files.writeString(backend.resolve("target/surefire-reports/TEST-Example.xml"), ExecutionJavaReportsTest.xml().replace("PRIVATE_CANARY", CANARY));
            Files.writeString(backend.resolve("target/surefire-reports/Example.txt"), CANARY);
            var java = reports.read(budget());
            String combined = JSON.writeValueAsString(Map.of("frontend", frontend, "java", java));
            assertThat(combined).contains("\"tests\":1").doesNotContain(CANARY, temporary.toString(), "system-out", "testResults");
        }
    }

    private ExecutionProcess.Deadline budget() { return new ExecutionProcess.Deadline(Duration.ofSeconds(30)); }
    public static final class Child {
        public static void main(String[] args) {
            System.out.print(CANARY); System.err.print(CANARY);
            if (args[0].equals("failure")) System.exit(7);
        }
    }
}
