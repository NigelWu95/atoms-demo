package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionProcessTest {
    @TempDir Path temporary;

    @Test void childGetsOnlyExplicitEnvironmentAndLiteralImmutableArguments() throws Exception {
        var arguments = args("environment", ";$(touch must-not-exist)");
        var invocation = invocation(arguments);
        arguments.add("PRIVATE_MUTATION_CANARY");
        var builder = invocation.builder();
        assertThat(builder.command()).doesNotContain("PRIVATE_MUTATION_CANARY");
        assertThat(builder.environment().keySet()).doesNotContain("ANTHROPIC_API_KEY", "DEEPSEEK_API_KEY", "HTTP_PROXY",
                "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "NODE_OPTIONS", "SPRING_DATASOURCE_PASSWORD", "SMTP_PASSWORD");
        assertThat(builder.environment().keySet()).containsExactlyInAnyOrder("PATH", "HOME", "JAVA_HOME",
                "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL", "TZ", "CI", "NO_COLOR", "TERM", "MAVEN_SKIP_RC",
                "npm_config_userconfig", "npm_config_globalconfig", "npm_config_cache");
        var result = new ExecutionProcess().run(invocation, deadline(5), () -> false,
                UUID.randomUUID(), UUID.randomUUID(), () -> {});
        assertThat(result.summary().stdoutBytes()).isEqualTo(6);
        assertThat(result.summary().stderrBytes()).isEqualTo("PRIVATE_STDERR_CANARY!".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(result.toString()).doesNotContain("PRIVATE_STDERR_CANARY", temporary.toString());
        assertThat(temporary.resolve("must-not-exist")).doesNotExist();
    }

    @Test void failsClosedForExitStartPreflightCancellationAndExpiredBudgets() throws Exception {
        assertFailure("exit", deadline(5), () -> false, "EXECUTION_PROCESS_FAILED", true);
        assertFailure("hang", deadline(5), () -> true, "EXECUTION_CANCELLED", true);
        var clock = new AtomicLong();
        var expired = new ExecutionProcess.Deadline(Duration.ofSeconds(1), clock::get);
        clock.set(1_000_000_000L);
        assertFailure("hang", expired, () -> false, "EXECUTION_TIMEOUT", true);
        var missing = new ExecutionProcess.Invocation(temporary.resolve("missing"), List.of(), temporary,
                temporary, temporary, Path.of(System.getProperty("java.home")), temporary);
        assertThatThrownBy(() -> new ExecutionProcess().run(missing, deadline(5), () -> false,
                UUID.randomUUID(), UUID.randomUUID(), () -> {}))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("EXECUTION_START_FAILED");
                    assertThat(error.stopped()).isNull();
                });
        assertThatThrownBy(() -> new ExecutionProcess().run(invocation(args("success")), deadline(5), () -> false,
                UUID.randomUUID(), UUID.randomUUID(), () -> { throw new java.io.IOException("EXECUTION_TOOL_CHANGED"); }))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("EXECUTION_TOOL_CHANGED");
                    assertThat(error.stopped()).isNotNull();
                });
    }

    @Test void floodsOnEitherStreamStopWithoutSavingRawOutput() throws Exception {
        for (String mode : List.of("stdout-flood", "stderr-flood"))
            assertFailure(mode, deadline(5), () -> false, "EXECUTION_OUTPUT_LIMIT", true);
    }

    @Test void cancellationStopsOnlyOurChildAndTimeoutHasOneCleanupBudget() throws Exception {
        var other = invocation(args("other")).builder().start();
        try {
            assertFailure("hang", deadline(5), () -> Files.exists(temporary.resolve("pid")),
                    "EXECUTION_CANCELLED", true);
            assertThat(other.isAlive()).isTrue();
        } finally {
            other.destroyForcibly();
            assertThat(other.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        long started = System.nanoTime();
        assertFailure("ignore-term", new ExecutionProcess.Deadline(Duration.ofMillis(750)), () -> false,
                "EXECUTION_TIMEOUT", true);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(12));
    }

    @Test void blockedDecoderAndRepeatedInterruptsCannotExtendCleanupOrFabricateProof() throws Exception {
        var decoding = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                new OwnedProcess().runCommand(invocation(args("success")).builder(), deadline(20)::remaining,
                        () -> false, UUID.randomUUID(), UUID.randomUUID(), stream -> {
                            stream.readAllBytes(); decoding.countDown();
                            while (release.getCount() != 0) {
                                try { release.await(); } catch (InterruptedException ignored) { }
                            }
                            return null;
                        }, stream -> { stream.readAllBytes(); return null; }, () -> {});
            } catch (Throwable error) { failure.set(error); }
        }, "execution-blocked-decoder-test");
        worker.start();
        long started = System.nanoTime();
        try {
            assertThat(decoding.await(5, TimeUnit.SECONDS)).isTrue();
            while (worker.isAlive() && System.nanoTime() - started < Duration.ofSeconds(14).toNanos()) {
                worker.interrupt(); worker.join(30);
            }
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                assertThat(error.getMessage()).isEqualTo("EXECUTION_PROCESS_UNSETTLED");
                assertThat(error.stopped()).isNull();
            });
        } finally { release.countDown(); worker.join(12000); }
    }

    @Test void totalDeadlineDoesNotResetBetweenCommandsAndRejectsExpandedLimits() throws Exception {
        var clock = new AtomicLong(100);
        var deadline = new ExecutionProcess.Deadline(Duration.ofSeconds(10), clock::get);
        clock.addAndGet(7_000_000_000L);
        assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(3));
        clock.addAndGet(3_000_000_000L);
        assertThatThrownBy(deadline::remaining).hasMessage("EXECUTION_TIMEOUT");
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(1801)))
            assertThatThrownBy(() -> new ExecutionProcess.Deadline(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionProcess.Invocation(Path.of("java"), List.of(), temporary,
                temporary, temporary, temporary, temporary)).hasMessage("EXECUTION_INVOCATION_REJECTED");
    }

    private void assertFailure(String mode, ExecutionProcess.Deadline deadline,
                               java.util.function.BooleanSupplier cancel, String code, boolean stopped) throws Exception {
        UUID task = UUID.randomUUID(), execution = UUID.randomUUID();
        assertThatThrownBy(() -> new ExecutionProcess().run(invocation(args(mode)), deadline, cancel, task, execution, () -> {}))
                .isInstanceOfSatisfying(OwnedProcess.Failure.class, error -> {
                    assertThat(error.getMessage()).isEqualTo(code);
                    if (stopped) assertThat(error.stopped().matches(task, execution, temporary)).isTrue();
                    else assertThat(error.stopped()).isNull();
                });
        if (Files.exists(temporary.resolve("pid"))) {
            long pid = Long.parseLong(Files.readString(temporary.resolve("pid")));
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }
    private ExecutionProcess.Invocation invocation(List<String> args) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        return new ExecutionProcess.Invocation(javaHome.resolve("bin/java"), args, temporary,
                temporary, temporary, javaHome, temporary);
    }
    private ArrayList<String> args(String mode, String... more) {
        var args = new ArrayList<>(List.of("-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
                Fixture.class.getName(), mode));
        args.addAll(List.of(more)); return args;
    }
    private ExecutionProcess.Deadline deadline(int seconds) { return new ExecutionProcess.Deadline(Duration.ofSeconds(seconds)); }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            if (args[0].equals("other")) Thread.sleep(60000);
            if (args[0].equals("environment")) {
                if (!args[1].equals(";$(touch must-not-exist)") || args.length != 2) System.exit(4);
                if (System.getenv().keySet().stream().anyMatch(key -> key.contains("PASSWORD") || key.contains("API_KEY")
                        || key.contains("PROXY") || key.equals("JAVA_TOOL_OPTIONS") || key.equals("NODE_OPTIONS"))) System.exit(5);
            }
            if (args[0].equals("ignore-term")) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Thread.sleep(60000); } catch (InterruptedException ignored) { }
            }));
            if (args[0].equals("hang") || args[0].equals("ignore-term")) {
                Files.writeString(Path.of("pending-pid"), Long.toString(ProcessHandle.current().pid()));
                Files.move(Path.of("pending-pid"), Path.of("pid"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(60000);
            }
            if (args[0].equals("stdout-flood")) { while (true) System.out.write(new byte[8192]); }
            if (args[0].equals("stderr-flood")) { while (true) System.err.write(new byte[8192]); }
            System.out.print("stdout"); System.err.print("PRIVATE_STDERR_CANARY!");
            if (args[0].equals("exit")) System.exit(3);
        }
    }
}
