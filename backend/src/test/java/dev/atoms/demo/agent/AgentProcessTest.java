package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class AgentProcessTest {
    @TempDir Path temporary;

    @Test void failedPreflightIssuesBoundProofThatNoProcessWasLaunched() {
        var task = java.util.UUID.randomUUID(); var execution = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> new AgentProcess().run(command("success"), "test", new AgentDeadline(Duration.ofSeconds(8)),
                () -> false, task, execution, () -> { throw new java.io.IOException("AGENT_CLI_CHANGED"); }))
                .isInstanceOfSatisfying(AgentProcess.Failure.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("AGENT_CLI_CHANGED");
                    assertThat(error.stopped()).isNotNull();
                    assertThat(error.stopped().matches(task, execution, temporary)).isTrue();
                });
        assertThat(temporary.resolve("parent.pid")).doesNotExist();
    }

    @Test void stoppedReceiptIsBoundToItsTaskExecutionAndWorkingDirectory() throws Exception {
        var task = java.util.UUID.randomUUID(); var execution = java.util.UUID.randomUUID();
        var result = new AgentProcess().run(command("success"), "test", new AgentDeadline(Duration.ofSeconds(8)),
                () -> false, task, execution);
        var proof = result.stopped();
        assertThat(proof.matches(task, execution, temporary)).isTrue();
        assertThat(proof.matches(java.util.UUID.randomUUID(), execution, temporary)).isFalse();
        assertThat(proof.matches(task, java.util.UUID.randomUUID(), temporary)).isFalse();
        assertThat(proof.matches(task, execution, temporary.resolve("different"))).isFalse();
        assertThat(proof.digest()).matches("[a-f0-9]{64}");
        assertThat(proof.toString()).doesNotContain(temporary.toString(), "test");
        assertThat(proof.getClass().getConstructors()).isEmpty();
    }

    @Test void requiresSuccessfulProtocolAndProcessExitAndProducesOwnedReceipt() throws Exception {
        var result = execute("success", Duration.ofSeconds(8));
        assertThat(result.summary().events()).isEqualTo(2);
        assertThat(result.stopped()).isNotNull();
        assertThat(result.toString()).doesNotContain("PRIVATE_PROMPT", temporary.toString());
        assertThatThrownBy(() -> execute("exit-error", Duration.ofSeconds(8)))
                .isInstanceOf(AgentProcess.Failure.class).hasMessage("AGENT_PROCESS_FAILED");
        assertThatThrownBy(() -> execute("truncated", Duration.ofSeconds(8)))
                .isInstanceOf(AgentProcess.Failure.class).hasMessage("AGENT_PROTOCOL_INVALID");
    }

    @Test void outputFloodsAreStoppedAndReportedWithoutRawOutput() {
        for (String mode : List.of("stdout-flood", "stderr-flood")) {
            assertThatThrownBy(() -> execute(mode, Duration.ofSeconds(8)))
                    .isInstanceOf(AgentProcess.Failure.class).hasMessage("AGENT_OUTPUT_LIMIT")
                    .satisfies(error -> assertThat(((AgentProcess.Failure) error).stopped()).isNotNull());
        }
    }

    @ParameterizedTest
    @CsvSource({"authentication_failed,AGENT_PROVIDER_AUTH_FAILED", "rate_limit,AGENT_PROVIDER_RATE_LIMITED",
            "server_error,AGENT_PROVIDER_UNAVAILABLE", "unknown,AGENT_PROVIDER_ERROR"})
    void providerFailuresSurviveTheProcessBoundaryWithAStoppedReceipt(String mode, String code) throws Exception {
        var task = java.util.UUID.randomUUID(); var execution = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> new AgentProcess().run(command(mode), "PRIVATE_PROMPT", new AgentDeadline(Duration.ofSeconds(8)),
                () -> false, task, execution)).isInstanceOfSatisfying(AgentProcess.Failure.class, error -> {
                    assertThat(error.getMessage()).isEqualTo(code);
                    assertThat(error.stopped()).isNotNull();
                    assertThat(error.stopped().matches(task, execution, temporary)).isTrue();
                    assertThat(error.getCause()).isNull();
                });
        assertStopped(temporary.resolve("parent.pid"));
    }

    @Test void protocolRejectionLogsOnlyFixedMetadataAndStillStopsProcess() throws Exception {
        var task = java.util.UUID.randomUUID(); var execution = java.util.UUID.randomUUID();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgentProcess.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> new AgentProcess().run(command("private-protocol-error"), "PRIVATE_PROMPT",
                    new AgentDeadline(Duration.ofSeconds(8)), () -> false, task, execution))
                    .isInstanceOfSatisfying(AgentProcess.Failure.class, error -> {
                        assertThat(error.getMessage()).isEqualTo("AGENT_PROTOCOL_INVALID");
                        assertThat(error.stopped()).isNotNull();
                        assertThat(error.stopped().matches(task, execution, temporary)).isTrue();
                    });
            assertThat(logs.list).hasSize(1);
            var log = logs.list.get(0);
            var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(log.getFormattedMessage());
            assertThat(event.size()).isEqualTo(5);
            assertThat(event.path("event").asText()).isEqualTo("agent_protocol_rejected");
            assertThat(event.path("requestId").asText()).isEqualTo(task.toString());
            assertThat(event.path("executionId").asText()).isEqualTo(execution.toString());
            assertThat(event.path("reason").asText()).isEqualTo("UNKNOWN_EVENT");
            assertThat(event.path("eventNumber").asInt()).isEqualTo(2);
            assertThat(event.toString()).doesNotContain("PRIVATE_", temporary.toString());
            assertThat(log.getThrowableProxy()).isNull();
            assertStopped(temporary.resolve("parent.pid"));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void expiredOrCancelledTasksNeverStartAProcess() {
        assertThatThrownBy(() -> new AgentProcess().run(command("success"), "", new AgentDeadline(Duration.ofSeconds(8)), () -> false))
                .hasMessage("AGENT_INVOCATION_REJECTED");
        var now = new AtomicLong();
        var deadline = new AgentDeadline(Duration.ofNanos(1), now::get);
        now.incrementAndGet();
        assertThatThrownBy(() -> new AgentProcess().run(command("success"), "test", deadline, () -> false))
                .isInstanceOfSatisfying(AgentProcess.Failure.class, error -> assertThat(error.stopped()).isNotNull())
                .hasMessage("AGENT_TIMEOUT");
        assertThatThrownBy(() -> new AgentProcess().run(command("success"), "test", new AgentDeadline(Duration.ofSeconds(8)), () -> true))
                .hasMessage("AGENT_CANCELLED");
        assertThat(temporary.resolve("parent.pid")).doesNotExist();
    }

    @Test void cancellationStopsOnlyTheOwnedProcess() throws Exception {
        Path otherPid = temporary.resolve("other.pid");
        Process other = command("hang", otherPid).start();
        try {
            assertThatThrownBy(() -> new AgentProcess().run(command("hang"), "test", new AgentDeadline(Duration.ofSeconds(8)),
                    () -> Files.exists(temporary.resolve("parent.pid"))))
                    .hasMessage("AGENT_CANCELLED")
                    .satisfies(error -> assertThat(((AgentProcess.Failure) error).stopped()).isNotNull());
            assertThat(other.isAlive()).isTrue();
            assertStopped(temporary.resolve("parent.pid"));
        } finally { other.destroyForcibly(); other.waitFor(); }
    }

    @Test void timeoutEscalatesTerminationWithoutRestartingItsBudget() throws Exception {
        long start = System.nanoTime();
        assertThatThrownBy(() -> execute("ignore-term", Duration.ofSeconds(1)))
                .hasMessage("AGENT_TIMEOUT")
                .satisfies(error -> assertThat(((AgentProcess.Failure) error).stopped()).isNotNull());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(12));
        assertStopped(temporary.resolve("parent.pid"));
    }

    @Test void liveDescendantHoldingPipeCannotProduceASuccessReceipt() throws Exception {
        assertThatThrownBy(() -> new AgentProcess().run(command("child-pipe", temporary.resolve("parent.pid"), temporary.resolve("child.pid")),
                "test", new AgentDeadline(Duration.ofSeconds(5)), () -> false))
                .isInstanceOf(AgentProcess.Failure.class)
                .satisfies(error -> assertThat(((AgentProcess.Failure) error).stopped()).isNotNull());
        assertStopped(temporary.resolve("parent.pid")); assertStopped(temporary.resolve("child.pid"));
    }

    @Test void monotonicDeadlineKeepsOriginalBudget() throws Exception {
        var now = new AtomicLong(100);
        var deadline = new AgentDeadline(Duration.ofSeconds(5), now::get);
        now.addAndGet(Duration.ofSeconds(4).toNanos());
        assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(1));
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThatThrownBy(deadline::remaining).hasMessage("AGENT_TIMEOUT");
    }

    @Test void blockedStdinSharesTheDeadlineAndCannotHoldTheWorker() throws Exception {
        long start = System.nanoTime();
        assertThatThrownBy(() -> new AgentProcess().run(command("hang"), "x".repeat(1 << 20),
                new AgentDeadline(Duration.ofSeconds(1)), () -> false))
                .hasMessage("AGENT_TIMEOUT")
                .satisfies(error -> assertThat(((AgentProcess.Failure) error).stopped()).isNotNull());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(12));
        assertStopped(temporary.resolve("parent.pid"));
    }

    @Test void unavailableDescendantEvidenceDoesNotIssueStoppedReceipt() {
        var runner = new AgentProcess(process -> { throw new SecurityException("PRIVATE_OS_ERROR"); });
        assertThatThrownBy(() -> runner.run(command("hang"), "test", new AgentDeadline(Duration.ofSeconds(5)), () -> false))
                .hasMessage("AGENT_PROCESS_UNSETTLED")
                .satisfies(error -> assertThat(((AgentProcess.Failure) error).stopped()).isNull());
    }

    private AgentProcess.Result execute(String mode, Duration timeout) throws Exception {
        return new AgentProcess().run(command(mode), "PRIVATE_PROMPT", new AgentDeadline(timeout), () -> false);
    }
    private ProcessBuilder command(String mode, Path... paths) {
        var argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(), AgentChildFixture.class.getName(), mode));
        if (paths.length == 0) argv.add(temporary.resolve("parent.pid").toString());
        else for (Path path : paths) argv.add(path.toString());
        var builder = new ProcessBuilder(argv).directory(temporary.toFile());
        builder.environment().clear();
        return builder;
    }
    private void assertStopped(Path pidFile) throws Exception {
        assertThat(pidFile).isRegularFile();
        long pid = Long.parseLong(Files.readString(pidFile));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}
