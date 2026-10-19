package dev.atoms.demo.agent;

import dev.atoms.demo.source.SourceAgentFixture;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** Fatal JVM-window simulation; only the original in-process owner retains real exit evidence. */
class ProjectAgentCrashIT extends AgentIntegrationSupport {
    enum Window { BEFORE_START, EXIT_BEFORE_RECEIPT, STOPPED_BEFORE_SEAL }

    @ParameterizedTest @EnumSource(Window.class)
    void reopeningNeverReplaysAnEditOrInfersExitFromCandidateFiles(Window window) throws Exception {
        var base = source.create(owner);
        var original = source.snapshot(owner, base.projectId(), base.versionId());
        var repository = new AgentTaskRepository(jdbc);
        var service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
        var task = service.submit(owner, base.projectId(),
                new ProjectAgentService.Input("edit", base.versionId().toString(), 1), "crash");
        var handle = service.handle(task.id());
        var adapter = new AgentFixtureAdapter(temporary, "project-edit");
        var receipt = new AtomicReference<CodingAgent.Result>();
        CodingAgent crashing = (job, work, deadline) -> {
            if (window == Window.BEFORE_START) throw new Crash();
            var session = adapter.prepare(job, work, deadline);
            return new CodingAgent.Session() {
                @Override public boolean paid() { return false; }
                @Override public CodingAgent.Result execute(UUID execution, java.util.function.BooleanSupplier cancelled) throws IOException {
                    var result = session.execute(execution, cancelled);
                    receipt.set(result);
                    if (window == Window.EXIT_BEFORE_RECEIPT) throw new Crash();
                    return result;
                }
                @Override public void close() { session.close(); }
            };
        };
        var lifecycle = new AgentTaskLifecycle(service, repository, projects, source.gateway(), transactions) {
            @Override AgentTask phase(UUID id, String expected, String next) {
                if (window == Window.STOPPED_BEFORE_SEAL && next.equals("ACCEPTING")) throw new Crash();
                return super.phase(id, expected, next);
            }
        };
        long budget = jdbc.queryForObject("SELECT used FROM agent_live_budget WHERE id", Long.class);
        try {
            var worker = new AgentWorker(service, repository, lifecycle, source.gateway(), crashing);
            assertThatThrownBy(() -> worker.execute(task.id())).isInstanceOf(Crash.class);
            source.close(); // Release the old process's storage lock before simulating a fresh owner.
            try (var reopened = new SourceAgentFixture(jdbc, projects, transactions, temporary.toRealPath())) {
                var recoveredService = new ProjectAgentService(settings, repository, projects, reopened.gateway(), transactions);
                var recovery = new AgentRecovery(repository, reopened.gateway(), transactions);
                for (int repeat = 0; repeat < 2; repeat++) {
                    var recovered = recovery.reconcile(task.id());
                    assertThat(recovered.status()).isEqualTo(window == Window.EXIT_BEFORE_RECEIPT ? "NEEDS_RECONCILIATION" : "FAILED");
                    assertThat(recovered.resultVersionId()).isNull();
                    assertThat(recoveredService.handle(task.id())).isNull();
                    assertThat(worker.execute(task.id()).status()).isEqualTo(recovered.status());
                }
                assertThat(reopened.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(original.sha256());
                assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, base.projectId()))
                        .isEqualTo(base.versionId());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
                var lease = jdbc.queryForMap("SELECT status,execution_pending,stopped_sha256 FROM source_edit_leases WHERE id=?", handle.leaseId());
                assertThat(lease.get("execution_pending")).isEqualTo(window == Window.EXIT_BEFORE_RECEIPT);
                assertThat(lease.get("status")).isEqualTo(window == Window.EXIT_BEFORE_RECEIPT ? "NEEDS_RECONCILIATION" : "RELEASED");
                if (window == Window.STOPPED_BEFORE_SEAL) assertThat((String) lease.get("stopped_sha256")).matches("[a-f0-9]{64}");
                else assertThat(lease.get("stopped_sha256")).isNull();
            }
            assertThat(adapter.calls.get()).isEqualTo(window == Window.BEFORE_START ? 0 : 1);
            assertThat(jdbc.queryForObject("SELECT used FROM agent_live_budget WHERE id", Long.class)).isEqualTo(budget);
        } finally {
            // Recovery never invents this receipt: it came from this exact execution above.
            if (window == Window.EXIT_BEFORE_RECEIPT && receipt.get() != null) {
                source.gateway().confirmExecutionStopped(handle, receipt.get().stopped());
                assertThat(new AgentRecovery(repository, source.gateway(), transactions).reconcile(task.id()).status()).isEqualTo("FAILED");
            }
        }
    }

    private static final class Crash extends Error {}
}
