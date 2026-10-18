package dev.atoms.demo.agent;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Agent protocol adapter; the shared process owner issues execution-bound stopped evidence. */
final class AgentProcess {
    private final OwnedProcess owner;
    AgentProcess() { owner = new OwnedProcess(); }
    AgentProcess(java.util.function.Function<Process, List<ProcessHandle>> descendants) {
        owner = new OwnedProcess(descendants);
    }
    record Result(AgentStreamDecoder.Summary summary, OwnedProcess.Stopped stopped) {}
    static final class Failure extends IOException {
        private final OwnedProcess.Stopped stopped;
        private Failure(String code, OwnedProcess.Stopped stopped) { super(code); this.stopped = stopped; }
        OwnedProcess.Stopped stopped() { return stopped; }
    }
    Result run(ProcessBuilder builder, String prompt, AgentDeadline deadline, BooleanSupplier cancelled) throws IOException {
        return run(builder, prompt, deadline, cancelled, UUID.randomUUID(), UUID.randomUUID());
    }
    Result run(ProcessBuilder builder, String prompt, AgentDeadline deadline, BooleanSupplier cancelled,
               UUID task, UUID execution) throws IOException {
        if (prompt == null || prompt.isEmpty()) throw new Failure("AGENT_INVOCATION_REJECTED", null);
        try {
            var result = owner.run(builder, prompt, deadline::remaining, cancelled, task, execution,
                    stream -> new AgentStreamDecoder(builder.directory().toPath()).read(stream));
            return new Result(result.summary(), result.stopped());
        } catch (OwnedProcess.Failure error) { throw new Failure(error.getMessage(), error.stopped()); }
    }
}
