package dev.atoms.demo.agent;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Trusted internal adapter, never a client-selected command or transport. */
interface CodingAgent {
    Session prepare(AgentTask task, Path work, AgentDeadline deadline) throws IOException;
    interface Session extends AutoCloseable {
        boolean paid();
        Result execute(UUID execution, BooleanSupplier cancelled) throws IOException;
        @Override void close();
    }
    record Result(AgentStreamDecoder.Summary summary, OwnedProcess.Stopped stopped) {
        public Result { java.util.Objects.requireNonNull(summary); java.util.Objects.requireNonNull(stopped); }
    }
    final class Failure extends IOException {
        private final OwnedProcess.Stopped stopped;
        Failure(String code, OwnedProcess.Stopped stopped) { super(code); this.stopped = stopped; }
        OwnedProcess.Stopped stopped() { return stopped; }
    }
}
