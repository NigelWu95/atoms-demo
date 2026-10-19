package dev.atoms.demo.agent;

import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AgentProcessInterruptionTest {
    @TempDir Path temporary;

    @Test void repeatedCancellationStillWaitsForOwnedIoAndRestoresTheInterruptFlag() throws Exception {
        var draining = new CountDownLatch(1); var interruptedIo = new CountDownLatch(1); var release = new CountDownLatch(1);
        var result = new AtomicReference<Throwable>(); var interruptRestored = new AtomicBoolean();
        var worker = new Thread(() -> {
            try {
                new OwnedProcess().run(command(), "fixture", new AgentDeadline(Duration.ofSeconds(10))::remaining,
                        () -> false, UUID.randomUUID(), UUID.randomUUID(), stream -> {
                            stream.readAllBytes(); draining.countDown();
                            boolean interrupted = false;
                            try {
                                while (release.getCount() != 0) {
                                    try { release.await(); }
                                    catch (InterruptedException error) { interrupted = true; interruptedIo.countDown(); }
                                }
                                return "drained";
                            } finally { if (interrupted) Thread.currentThread().interrupt(); }
                        });
                result.set(new AssertionError("Interrupted execution succeeded"));
            } catch (Throwable error) { result.set(error); }
            finally { interruptRestored.set(Thread.currentThread().isInterrupted()); }
        }, "owned-process-interruption-test");
        worker.start();
        try {
            assertThat(draining.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            assertThat(interruptedIo.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(100);
            assertThat(worker.isAlive()).as("An extra interrupt must not abandon owned I/O before its deadline").isTrue();
        } finally {
            release.countDown(); worker.join(12000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get()).isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
            assertThat(error.getMessage()).isEqualTo("AGENT_CANCELLED");
            assertThat(error.stopped()).isNotNull();
        });
        assertThat(interruptRestored).isTrue();
    }

    @Test void unresolvedProcessLogsOnlyCorrelatedAllowlistedDiagnostics(CapturedOutput output) {
        var task = UUID.randomUUID(); var execution = UUID.randomUUID();
        var runner = new OwnedProcess(process -> { throw new SecurityException("PRIVATE_PROCESS_CANARY"); });
        assertThatThrownBy(() -> runner.run(command(), "PRIVATE_PROMPT_CANARY", new AgentDeadline(Duration.ofSeconds(5))::remaining,
                () -> false, task, execution, stream -> stream.readAllBytes()))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> assertThat(error.stopped()).isNull());
        assertThat(output.getOut()).contains("\"event\":\"owned_process_unsettled\"", "\"entryPoint\":\"owned_process\"",
                "\"requestId\":\"" + task + "\"", "\"executionId\":\"" + execution + "\"",
                "\"reason\":\"DESCENDANT_ENUMERATION_FAILED\"");
        assertThat(output.getAll()).doesNotContain("PRIVATE_PROCESS_CANARY", "PRIVATE_PROMPT_CANARY", temporary.toString());
    }

    private ProcessBuilder command() {
        var builder = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), AgentChildFixture.class.getName(), "success"))
                .directory(temporary.toFile());
        builder.environment().clear();
        return builder;
    }
}
