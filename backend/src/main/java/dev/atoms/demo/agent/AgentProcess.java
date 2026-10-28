package dev.atoms.demo.agent;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Agent protocol adapter; the shared process owner issues execution-bound stopped evidence. */
final class AgentProcess {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentProcess.class);
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
        return run(builder, prompt, deadline, cancelled, task, execution, () -> {});
    }
    Result run(ProcessBuilder builder, String prompt, AgentDeadline deadline, BooleanSupplier cancelled,
               UUID task, UUID execution, OwnedProcess.Preflight preflight) throws IOException {
        if (prompt == null || prompt.isEmpty()) throw new Failure("AGENT_INVOCATION_REJECTED", null);
        try {
            var result = owner.run(builder, prompt, deadline::remaining, cancelled, task, execution,
                    stream -> {
                        try { return new AgentStreamDecoder(builder.directory().toPath()).read(stream); }
                        catch (AgentStreamDecoder.ProtocolFailure error) {
                            LOG.warn("{\"event\":\"agent_protocol_rejected\",\"requestId\":\"{}\",\"executionId\":\"{}\",\"reason\":\"{}\",\"eventNumber\":{},\"systemSubtypeSha256\":\"{}\"}",
                                    task, execution, error.rejection(), error.eventNumber(), error.systemSubtypeSha256());
                            throw error;
                        } catch (AgentStreamDecoder.OutputLimit error) {
                            LOG.warn("{\"event\":\"agent_stream_limited\",\"requestId\":\"{}\",\"executionId\":\"{}\",\"reason\":\"{}\",\"eventNumber\":{}}",
                                    task, execution, error.reason(), error.eventNumber());
                            throw error;
                        } catch (AgentStreamDecoder.Incomplete error) {
                            LOG.warn("{\"event\":\"agent_result_incomplete\",\"requestId\":\"{}\",\"executionId\":\"{}\",\"reason\":\"{}\",\"turns\":{}}",
                                    task, execution, error.reason(), error.turns());
                            throw error;
                        }
                    }, preflight);
            return new Result(result.summary(), result.stopped());
        } catch (OwnedProcess.Failure error) { throw new Failure(error.getMessage(), error.stopped()); }
    }
}
