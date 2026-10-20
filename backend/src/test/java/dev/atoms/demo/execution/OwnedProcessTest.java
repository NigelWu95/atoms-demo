package dev.atoms.demo.execution;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class OwnedProcessTest {
    @TempDir Path temporary;

    @Test void commandAdapterKeepsBoundProofAndSeparateBoundedStreams() throws Exception {
        UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
        var result = new OwnedProcess().runCommand(command("success"), () -> Duration.ofSeconds(5), () -> false,
                task, execution, stream -> new String(stream.readNBytes(128)),
                stream -> { assertThat(new String(stream.readNBytes(128))).isEqualTo("private-stderr"); return null; }, () -> {});
        assertThat(result.summary()).isEqualTo("stdout");
        assertThat(result.stopped().matches(task, execution, temporary)).isTrue();
    }

    @Test void commandFailuresNeverExposeRawPreflightOrDecoderOrBudgetText() {
        for (String mode : List.of("preflight", "decoder", "budget")) {
            assertThatThrownBy(() -> new OwnedProcess().runCommand(command("success"),
                    () -> { if (mode.equals("budget")) throw new IOException("PRIVATE_BUDGET"); return Duration.ofSeconds(5); },
                    () -> false, UUID.randomUUID(), UUID.randomUUID(),
                    stream -> { if (mode.equals("decoder")) throw new IOException("PRIVATE_STREAM"); return stream.readNBytes(128); },
                    stream -> null, () -> { if (mode.equals("preflight")) throw new IOException("PRIVATE_PATH"); }))
                    .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                        assertThat(error.getMessage()).isIn("EXECUTION_PREFLIGHT_FAILED", "EXECUTION_IO_FAILED", "EXECUTION_TIMEOUT");
                        assertThat(error.stopped()).isNotNull();
                        assertThat(error.getCause()).isNull();
                    });
        }
    }

    @Test void commandUnknownDescendantsNeverIssueAStoppedReceipt() {
        assertThatThrownBy(() -> new OwnedProcess(process -> { throw new SecurityException("PRIVATE_OS"); })
                .runCommand(command("hang"), () -> Duration.ofSeconds(5), () -> false,
                        UUID.randomUUID(), UUID.randomUUID(), stream -> stream.readNBytes(128), stream -> null, () -> {}))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("EXECUTION_PROCESS_UNSETTLED");
                    assertThat(error.stopped()).isNull();
                });
    }

    @Test void nonPositiveCommandBudgetCannotLaunch() {
        assertThatThrownBy(() -> new OwnedProcess().runCommand(command("hang"), () -> Duration.ZERO, () -> false,
                UUID.randomUUID(), UUID.randomUUID(), stream -> null, stream -> null, () -> {}))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("EXECUTION_TIMEOUT");
                    assertThat(error.stopped()).isNotNull();
                });
    }

    private ProcessBuilder command(String mode) {
        var builder = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(), Fixture.class.getName(), mode))
                .directory(temporary.toFile());
        builder.environment().clear();
        return builder;
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            if (args[0].equals("hang")) Thread.sleep(60000);
            System.out.print("stdout");
            System.err.print("private-stderr");
        }
    }
}
