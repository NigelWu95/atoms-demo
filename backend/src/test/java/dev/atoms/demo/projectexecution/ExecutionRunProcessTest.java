package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionRunProcessTest {
    @TempDir Path temporary;
    private final UUID run = UUID.randomUUID(), execution = UUID.randomUUID();

    @Test void healthyProcessOutlivesStartupBudgetAndRepeatedStopSharesRealReceipt() throws Exception {
        var clock = new AtomicLong();
        var process = start("hang", new ExecutionProcess.Deadline(Duration.ofSeconds(1), clock::get));
        try {
            eventually(process::isAlive);
            assertThat(process.confirmReady(UUID.randomUUID(), execution)).isFalse();
            assertThat(process.confirmReady(run, UUID.randomUUID())).isFalse();
            assertThat(process.isServing()).isFalse();
            assertThat(process.confirmReady(run, execution)).isTrue();
            clock.set(Duration.ofSeconds(20).toNanos());
            assertThatThrownBy(() -> process.await(Duration.ofMillis(150))).isInstanceOf(TimeoutException.class);
            assertThat(process.isServing()).isTrue();
            assertThat(process.confirmReady(run, execution)).isTrue();
            process.requestStop(); process.requestStop();
            assertThat(process.confirmReady(run, execution)).isFalse();
            var stopped = process.await(Duration.ofSeconds(12));
            assertThat(stopped.code()).isEqualTo("RUN_STOPPED");
            assertThat(stopped.stopped().matches(run, execution, temporary)).isTrue();
            assertThat(process.await(Duration.ofMillis(1))).isSameAs(stopped);
            assertThat(process.isAlive()).isFalse(); assertThat(process.isServing()).isFalse();
        } finally { settle(process); }
    }

    @Test void missingAndLateHealthDoNotExtendStartupWindow() throws Exception {
        var clock = new AtomicLong();
        var process = start("hang", new ExecutionProcess.Deadline(Duration.ofSeconds(1), clock::get));
        try {
            eventually(process::isAlive); clock.set(Duration.ofSeconds(2).toNanos());
            assertThat(process.confirmReady(run, execution)).isFalse();
            process.requestStop(); // A late stop must not replace the already observed timeout.
            var outcome = process.await(Duration.ofSeconds(12));
            assertThat(outcome.code()).isEqualTo("RUN_START_TIMEOUT");
            assertThat(outcome.stopped()).isNotNull();
            assertThat(process.confirmReady(run, execution)).isFalse();
        } finally { settle(process); }
    }

    @Test void outputFloodIsContinuouslyDrainedWithoutAProcessLifetimeByteLimit() throws Exception {
        var process = start("flood", new ExecutionProcess.Deadline(Duration.ofSeconds(10)));
        try {
            eventually(process::isAlive); assertThat(process.confirmReady(run, execution)).isTrue();
            eventually(() -> process.output().stdoutBytes() >= (18L << 20) && process.output().stderrBytes() >= (18L << 20));
            assertThat(process.isServing()).isTrue();
            process.requestStop();
            var outcome = process.await(Duration.ofSeconds(12));
            assertThat(outcome.code()).isEqualTo("RUN_STOPPED");
            assertThat(outcome.output().stdoutBytes()).isGreaterThanOrEqualTo(18L << 20);
            assertThat(outcome.output().stderrBytes()).isGreaterThanOrEqualTo(18L << 20);
        } finally { settle(process); }
    }

    @Test void ownerEnforcesStartupTimeoutWithoutAHealthPoll() throws Exception {
        var clock = new AtomicLong();
        var process = start("hang", new ExecutionProcess.Deadline(Duration.ofSeconds(1), clock::get));
        try {
            eventually(process::isAlive); clock.set(Duration.ofSeconds(2).toNanos());
            var outcome = process.await(Duration.ofSeconds(12));
            assertThat(outcome.code()).isEqualTo("RUN_START_TIMEOUT");
            assertThat(outcome.stopped().matches(run, execution, temporary)).isTrue();
            assertThat(process.isAlive()).isFalse();
        } finally { settle(process); }
    }

    @Test void stoppingBetweenCancellationCheckAndPollDoesNotReopenStartupDeadline() throws Exception {
        var observing = new CountDownLatch(1); var release = new CountDownLatch(1); var clock = new AtomicLong();
        var owner = new OwnedProcess(child -> {
            observing.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            return java.util.List.of();
        });
        var process = ExecutionRunProcess.start(owner, command("hang"), run, execution,
                new ExecutionProcess.Deadline(Duration.ofSeconds(1), clock::get), () -> {});
        try {
            assertThat(observing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(process.confirmReady(run, execution)).isTrue();
            clock.set(Duration.ofSeconds(2).toNanos()); process.requestStop(); release.countDown();
            var outcome = process.await(Duration.ofSeconds(12));
            assertThat(outcome.code()).isEqualTo("RUN_STOPPED");
            assertThat(outcome.stopped().matches(run, execution, temporary)).isTrue();
        } finally { release.countDown(); settle(process); }
    }

    @Test void cancellationDuringPreflightCannotLaunchOrBecomeReady() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var process = ExecutionRunProcess.start(command("hang"), run, execution,
                new ExecutionProcess.Deadline(Duration.ofSeconds(10)), () -> {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("PRIVATE"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("PRIVATE"); }
                });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            process.requestStop(); release.countDown();
            var outcome = process.await(Duration.ofSeconds(12));
            assertThat(outcome.code()).isEqualTo("RUN_STOPPED");
            assertThat(outcome.stopped().matches(run, execution, temporary)).isTrue();
            assertThat(process.isAlive()).isFalse(); assertThat(process.confirmReady(run, execution)).isFalse();
        } finally { release.countDown(); settle(process); }
    }

    @Test void exitAndLaunchFailureKeepDifferentStopEvidence() throws Exception {
        for (String mode : new String[]{"exit", "fail"}) {
            var process = start(mode, new ExecutionProcess.Deadline(Duration.ofSeconds(10)));
            try {
                var outcome = process.await(Duration.ofSeconds(12));
                assertThat(outcome.code()).isEqualTo(mode.equals("exit") ? "RUN_EXITED" : "RUN_PROCESS_FAILED");
                assertThat(outcome.stopped()).isNotNull();
                assertThat(process.confirmReady(run, execution)).isFalse();
            } finally { settle(process); }
        }
        var builder = command("hang"); builder.command().set(0, temporary.resolve("absent-java").toString());
        var failed = ExecutionRunProcess.start(builder, run, execution, new ExecutionProcess.Deadline(Duration.ofSeconds(10)), () -> {});
        var outcome = failed.await(Duration.ofSeconds(12));
        assertThat(outcome.code()).isEqualTo("RUN_START_FAILED"); assertThat(outcome.stopped()).isNull();
        failed.requestStop(); assertThat(failed.await(Duration.ofMillis(1))).isSameAs(outcome);
    }

    @Test void unknownDescendantsNeverProduceStopProof() throws Exception {
        var owner = new OwnedProcess(process -> { throw new SecurityException("PRIVATE-canary"); });
        var process = ExecutionRunProcess.start(owner, command("hang"), run, execution,
                new ExecutionProcess.Deadline(Duration.ofSeconds(10)), () -> {});
        try {
            var outcome = process.await(Duration.ofSeconds(12));
            assertThat(outcome.code()).isEqualTo("RUN_PROCESS_UNSETTLED"); assertThat(outcome.stopped()).isNull();
            assertThat(outcome.toString()).doesNotContain("PRIVATE-canary");
            assertThat(process.isServing()).isFalse();
        } finally { settle(process); }
    }

    private ExecutionRunProcess start(String mode, OwnedProcess.Budget budget) {
        return ExecutionRunProcess.start(command(mode), run, execution, budget, () -> {});
    }
    private ProcessBuilder command(String mode) {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), Fixture.class.getName(), mode).directory(temporary.toFile());
        builder.environment().clear(); return builder;
    }
    private static void settle(ExecutionRunProcess process) throws Exception { process.requestStop(); process.await(Duration.ofSeconds(12)); }
    private static void eventually(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            if (args[0].equals("exit")) return;
            if (args[0].equals("fail")) System.exit(7);
            if (args[0].equals("flood")) {
                byte[] bytes = new byte[65536];
                for (int i = 0; i < 288; i++) { System.out.write(bytes); System.err.write(bytes); }
                System.out.flush(); System.err.flush();
            }
            Thread.sleep(60000);
        }
    }
}
