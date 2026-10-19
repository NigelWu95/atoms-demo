package dev.atoms.demo.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Only test resources use this deterministic adapter; production always selects ClaudeCodeAgent. */
final class AgentFixtureAdapter implements CodingAgent {
    final AtomicInteger calls = new AtomicInteger();
    final java.util.Map<UUID, Path> work = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path state;
    private final String mode;
    AgentFixtureAdapter(Path state, String mode) { this.state = state; this.mode = mode; }
    Path pid(UUID task) { return state.resolve(task + ".pid"); }
    @Override public Session prepare(AgentTask task, Path work, AgentDeadline deadline) {
        this.work.put(task.id(), work);
        return new Session() {
            @Override public boolean paid() { return false; }
            @Override public Result execute(UUID execution, java.util.function.BooleanSupplier cancelled) throws IOException {
                int round = calls.incrementAndGet();
                var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                        Path.of("target/test-classes").toAbsolutePath().toString(), AgentChildFixture.class.getName(), mode,
                        pid(task.id()).toString(), Integer.toString(round)).directory(work.toFile());
                command.environment().clear();
                try {
                    var result = new AgentProcess().run(command, "fixture", deadline, cancelled, task.id(), execution);
                    return new Result(result.summary(), result.stopped());
                } catch (AgentProcess.Failure error) { throw new CodingAgent.Failure(error.getMessage(), error.stopped()); }
            }
            @Override public void close() {}
        };
    }
}
