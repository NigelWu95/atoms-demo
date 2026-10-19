package dev.atoms.demo.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("agent-cli-contract")
class AgentCliJourneyContractIT extends NativeJourneySupport {
    @Test void installedCliEditsFullNativeProjectTwiceAndReopensTheSameVersions() throws Exception {
        String installed = System.getProperty("atoms.agent.test.cli");
        assertThat(installed).as("Explicit installed CLI path; never skip compatibility").isNotBlank();
        var calls = new AtomicInteger();
        var workspaces = new ArrayList<Path>();
        var fixtures = new ArrayList<AgentProtocolFixture>();
        CodingAgent adapter = (task, work, deadline) -> {
            workspaces.add(work);
            int round = calls.incrementAndGet();
            var fixture = new AgentProtocolFixture(Files.createDirectory(temporary.resolve("cli-round-" + round)).toRealPath(),
                    Path.of(installed), work);
            fixtures.add(fixture);
            final AgentCliProbe.Identity identity;
            try { identity = fixture.verifyCli(deadline); }
            catch (IOException | RuntimeException error) { fixture.close(); throw error; }
            return new CodingAgent.Session() {
                @Override public boolean paid() { return false; }
                @Override public CodingAgent.Result execute(UUID execution, java.util.function.BooleanSupplier cancelled) throws IOException {
                    try {
                        var result = fixture.editNativeProject(task, execution, deadline, cancelled, round, identity);
                        return new CodingAgent.Result(result.summary(), result.stopped());
                    } catch (AgentProcess.Failure error) { throw new CodingAgent.Failure(error.getMessage(), error.stopped()); }
                }
                @Override public void close() { fixture.close(); }
            };
        };
        twoRounds(adapter, "installed-cli-loopback");
        assertThat(calls).hasValue(2);
        assertThat(workspaces).hasSize(2).doesNotHaveDuplicates();
        for (int round = 1; round <= 2; round++) {
            var fixture = fixtures.get(round - 1);
            assertThat(fixture.failure()).isNull();
            assertThat(fixture.toolResults()).hasSize(7).allSatisfy((id, outcome) -> assertThat(outcome.error()).as(id).isFalse());
            assertThat(fixture.requests()).hasSize(8).allSatisfy(request -> {
                assertThat(request.apiKey()).isEqualTo("offline-fixture-key");
                assertThat(request.model()).isEqualTo("deepseek-flash");
                assertThat(request.maxTokens()).isEqualTo(128000);
                assertThat(request.tools()).containsExactlyInAnyOrder("Read", "Glob", "Grep", "Write", "Edit");
            });
            assertThat(fixture.toolResults().get("read_vue").content()).contains(NativeProjectEdits.before(round));
            assertThat(fixture.configurationCanarySeen()).isFalse();
            assertThat(fixture.outsideContentSeen()).isFalse();
        }
    }
}
