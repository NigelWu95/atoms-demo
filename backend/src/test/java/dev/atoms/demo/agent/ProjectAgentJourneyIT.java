package dev.atoms.demo.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentJourneyIT extends NativeJourneySupport {
    @Test void nativeTemplateSurvivesTwoEditingProcessesDiffAndReopen() throws Exception {
        var calls = new AtomicInteger();
        twoRounds(fixture("edit", calls), "controlled-java");
        assertThat(calls).hasValue(2);
    }

    @Test void noChangeAndIncompleteResultKeepTheFullNativeVersion() throws Exception {
        var base = initialize();
        var original = source.snapshot(owner, base.projectId(), base.versionId());
        for (String mode : new String[] {"unchanged", "incomplete"}) {
            var calls = new AtomicInteger();
            var task = tasks.submit(owner, base.projectId(), input(mode, base.versionId(), 1), mode);
            var worker = worker(fixture(mode, calls));
            var result = worker.execute(task.id());
            assertThat(result.status()).isEqualTo(mode.equals("unchanged") ? "NO_CHANGES" : "FAILED");
            assertThat(result.resultVersionId()).isNull();
            assertThat(worker.execute(task.id())).isEqualTo(result);
            assertThat(calls).hasValue(1);
            assertStopped(task.id());
            assertThat(source.snapshot(owner, base.projectId(), base.versionId()).sha256()).isEqualTo(original.sha256());
            assertCurrent(base.projectId(), base.versionId(), 1);
        }
    }

    private CodingAgent fixture(String mode, AtomicInteger calls) {
        return (task, work, deadline) -> new CodingAgent.Session() {
            @Override public boolean paid() { return false; }
            @Override public CodingAgent.Result execute(UUID execution, java.util.function.BooleanSupplier cancelled) throws IOException {
                int round = calls.incrementAndGet();
                var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                        Path.of("target/test-classes").toAbsolutePath().toString(), NativeProjectEdits.class.getName(), mode,
                        Integer.toString(round)).directory(work.toFile());
                command.environment().clear();
                try {
                    var result = new AgentProcess().run(command, "native fixture", deadline, cancelled, task.id(), execution);
                    return new CodingAgent.Result(result.summary(), result.stopped());
                } catch (AgentProcess.Failure error) { throw new CodingAgent.Failure(error.getMessage(), error.stopped()); }
            }
            @Override public void close() {}
        };
    }
}
