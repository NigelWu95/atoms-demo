package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

@Tag("agent-cli-contract")
class AgentCliContractIT {
    @TempDir Path temporary;

    @Test void installedCliSearchesAndCreatesFrontendBackendAndMigrationFiles() throws Exception {
        try (var fixture = new AgentProtocolFixture(temporary.toRealPath(), installedCli())) {
            var result = fixture.exerciseProjectTools();
            assertThat(result.exitCode()).isZero();
            assertThat(result.success()).isTrue();
            assertThat(result.permissionDenials()).isZero();
            assertThat(result.initialTools()).containsExactlyInAnyOrder("Read", "Glob", "Grep", "Write", "Edit");
            assertThat(fixture.toolResults()).hasSize(7).allSatisfy((id, outcome) -> assertThat(outcome.error()).isFalse());
            assertThat(fixture.toolResults().get("tool_glob").content()).contains("hello.txt");
            assertThat(fixture.toolResults().get("tool_grep").content()).contains("before");
            assertThat(Files.readString(fixture.project().resolve("frontend/src/App.vue"))).contains("<template>");
            assertThat(Files.readString(fixture.project().resolve("backend/src/main/java/demo/TaskController.java")))
                    .contains("class TaskController");
            assertThat(Files.readString(fixture.project().resolve("backend/src/main/resources/db/migration/V1__tasks.sql")))
                    .contains("create table tasks");
            assertThat(fixture.configurationCanarySeen()).isFalse();
            assertThat(fixture.summary().toolCounts()).containsEntry("Write", 3).containsEntry("Edit", 1);
            assertThat(fixture.summary().paths()).contains("frontend/src/App.vue", "backend/src/main/java/demo/TaskController.java");
        }
    }

    @Test void installedCliRejectsOutsideReadWriteAndSymlinkRead() throws Exception {
        try (var fixture = new AgentProtocolFixture(temporary.toRealPath(), installedCli())) {
            fixture.exerciseDeniedTools();
            assertThat(fixture.toolResults()).hasSize(3).allSatisfy((id, outcome) -> assertThat(outcome.error()).isTrue());
            assertThat(fixture.outsideContentSeen()).isFalse();
            assertThat(Files.exists(temporary.resolve("outside-write.txt"))).isFalse();
        }
    }

    private static Path installedCli() {
        String cli = System.getProperty("atoms.agent.test.cli");
        assertThat(cli).as("Explicit installed CLI path is required").isNotBlank();
        return Path.of(cli);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void installedCliReadsAndEditsAFileWithOnlyApprovedToolsAndNoRealProvider(boolean separateToolArguments) throws Exception {
        String cli = System.getProperty("atoms.agent.test.cli");
        assertThat(cli).as("Explicit installed CLI path is required; no skipped compatibility checks").isNotBlank();
        try (var fixture = new AgentProtocolFixture(temporary.toRealPath(), Path.of(cli))) {
            var result = fixture.edit(separateToolArguments);
            assertThat(result.exitCode()).as("CLI must exit successfully").isZero();
            assertThat(result.success()).as("CLI must report a successful result").isTrue();
            assertThat(result.permissionDenials()).isZero();
            assertThat(Files.readString(fixture.project().resolve("hello.txt"))).isEqualTo("after\n");
            assertThat(fixture.readReturnedOriginalContent()).isTrue();
            assertThat(fixture.editReturnedSuccess()).isTrue();
            assertThat(fixture.requests()).hasSize(3).allSatisfy(request -> {
                assertThat(request.apiKey()).isEqualTo("offline-fixture-key");
                assertThat(request.model()).isEqualTo("deepseek-flash");
                assertThat(request.maxTokens()).isEqualTo(128000);
                assertThat(request.tools()).containsExactlyInAnyOrder("Read", "Glob", "Grep", "Write", "Edit");
                assertThat(request.stream()).isTrue();
            });
        }
    }
}
