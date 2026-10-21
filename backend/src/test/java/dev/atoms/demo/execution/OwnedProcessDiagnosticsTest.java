package dev.atoms.demo.execution;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedProcessDiagnosticsTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"ACCESS_DENIED", "UNSUPPORTED", "IO_FAILURE", "CONCURRENT_CHANGE", "INVALID_STATE", "RUNTIME_FAILURE"})
    void observationFailuresExposeOnlyFixedCategoriesAndNeverIssueProof(String kind) throws Exception {
        RuntimeException failure = switch (kind) {
            case "ACCESS_DENIED" -> new SecurityException("PRIVATE_MESSAGE_CANARY");
            case "UNSUPPORTED" -> new UnsupportedOperationException("PRIVATE_MESSAGE_CANARY");
            case "IO_FAILURE" -> new java.io.UncheckedIOException(new IOException("PRIVATE_MESSAGE_CANARY"));
            case "CONCURRENT_CHANGE" -> new java.util.ConcurrentModificationException("PRIVATE_MESSAGE_CANARY");
            case "INVALID_STATE" -> new IllegalStateException("PRIVATE_MESSAGE_CANARY");
            default -> new PRIVATE_CLASS_CANARY();
        };
        var calls = new AtomicInteger();
        verifyLog(process -> {
            if (calls.incrementAndGet() == 1) throw failure;
            throw new IllegalArgumentException("PRIVATE_LATER_CANARY");
        }, "PROCESS_ENUMERATION", kind, false);
        assertThat(calls.get()).as("Cleanup still observes the owned process; it must not replace the first cause").isGreaterThan(1);
    }

    @Test void childIdentityFailureIsDistinguishedFromProcessEnumeration() throws Exception {
        var child = mock(ProcessHandle.class);
        when(child.info()).thenThrow(new IllegalStateException("PRIVATE_CHILD_CANARY"));
        verifyLog(process -> List.of(child), "CHILD_OBSERVATION", "INVALID_STATE", false);
    }

    @Test void interruptAtFailureIsRecordedWithoutConsumingOrLosingTheFlag() throws Exception {
        try {
            verifyLog(process -> {
                Thread.currentThread().interrupt();
                throw new SecurityException("PRIVATE_INTERRUPT_CANARY");
            }, "PROCESS_ENUMERATION", "ACCESS_DENIED", true);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private void verifyLog(Function<Process, List<ProcessHandle>> descendants, String stage,
                           String kind, boolean interrupted) throws Exception {
        var task = UUID.randomUUID(); var execution = UUID.randomUUID();
        var logger = (Logger) LoggerFactory.getLogger(OwnedProcess.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            assertThatThrownBy(() -> new OwnedProcess(descendants).runCommand(command(),
                    () -> Duration.ofNanos(deadline - System.nanoTime()), () -> false, task, execution,
                    stream -> stream.readNBytes(128), stream -> { stream.readNBytes(128); return null; }, () -> {}))
                    .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                        assertThat(error.getMessage()).isEqualTo("EXECUTION_PROCESS_UNSETTLED");
                        assertThat(error.stopped()).isNull();
                        assertThat(error.getCause()).isNull();
                    });
            assertThat(logs.list).hasSize(1);
            var event = logs.list.get(0);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("CANARY", temporary.toString(), "stdout", "private-stderr");
            var json = new ObjectMapper().readTree(event.getFormattedMessage());
            assertThat(json.size()).isEqualTo(8);
            assertThat(json.path("event").asText()).isEqualTo("owned_process_unsettled");
            assertThat(json.path("entryPoint").asText()).isEqualTo("owned_process");
            assertThat(json.path("requestId").asText()).isEqualTo(task.toString());
            assertThat(json.path("executionId").asText()).isEqualTo(execution.toString());
            assertThat(json.path("reason").asText()).isEqualTo("DESCENDANT_ENUMERATION_FAILED");
            assertThat(json.path("observationStage").asText()).isEqualTo(stage);
            assertThat(json.path("failureKind").asText()).isEqualTo(kind);
            assertThat(json.path("observationInterrupted").asBoolean()).isEqualTo(interrupted);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    private ProcessBuilder command() {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), OwnedProcessTest.Fixture.class.getName(), "hang")
                .directory(temporary.toFile());
        builder.environment().clear();
        return builder;
    }

    private static final class PRIVATE_CLASS_CANARY extends RuntimeException {}
}
