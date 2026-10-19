package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AgentProcessConcurrencyTest {
    @TempDir Path temporary;

    @RepeatedTest(3) void cancellingOneOfFourProcessesDoesNotWaitForTheOthers() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        var cancelled = new ArrayList<AtomicBoolean>();
        var results = new ArrayList<java.util.concurrent.Future<AgentProcess.Failure>>();
        try {
            for (int i = 0; i < 4; i++) {
                var cancel = new AtomicBoolean(); cancelled.add(cancel);
                Path pid = temporary.resolve(i + ".pid");
                var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                        Path.of("target/test-classes").toAbsolutePath().toString(), AgentChildFixture.class.getName(), "hang", pid.toString())
                        .directory(temporary.toFile());
                builder.environment().clear();
                results.add(executor.submit(() -> {
                    try {
                        new AgentProcess().run(builder, "fixture", new AgentDeadline(Duration.ofSeconds(30)), cancel::get);
                        throw new AssertionError("Cancelled process succeeded");
                    } catch (AgentProcess.Failure failure) { return failure; }
                }));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.isRegularFile(pid) && System.nanoTime() < until) Thread.sleep(20);
                assertThat(pid).isRegularFile();
            }
            cancelled.get(0).set(true);
            var first = results.get(0).get(12, TimeUnit.SECONDS);
            assertThat(first.getMessage()).isEqualTo("AGENT_CANCELLED");
            assertThat(first.stopped()).isNotNull();
            for (int i = 1; i < 4; i++) {
                assertThat(results.get(i).isDone()).isFalse();
                assertThat(ProcessHandle.of(Long.parseLong(Files.readString(temporary.resolve(i + ".pid"))))
                        .map(ProcessHandle::isAlive).orElse(false)).isTrue();
            }
        } finally {
            cancelled.forEach(flag -> flag.set(true));
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i).get(1, TimeUnit.SECONDS);
            assertThat(result.getMessage()).isEqualTo("AGENT_CANCELLED");
            assertThat(result.stopped()).isNotNull();
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(temporary.resolve(i + ".pid"))))
                    .map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }
}
