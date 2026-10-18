package dev.atoms.demo.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AgentCliProbeTest {
    @TempDir Path temporary;

    @Test void acceptsOnlyThePinnedVersionAndSuccessfulExit() throws Exception {
        AgentCliProbe.requireVersion(0, "2.1.302 (Claude Code)\n".getBytes(StandardCharsets.UTF_8));
        for (String value : new String[] {"2.1.301 (Claude Code)\n", "2.1.303 (Claude Code)\n", "2.1.302\n",
                "2.1.302 (Claude Code)\nsecret-canary", ""}) {
            assertThatThrownBy(() -> AgentCliProbe.requireVersion(0, value.getBytes(StandardCharsets.UTF_8)))
                    .hasMessage("AGENT_CLI_UNSUPPORTED");
        }
        assertThatThrownBy(() -> AgentCliProbe.requireVersion(1, "2.1.302 (Claude Code)\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("AGENT_CLI_UNSUPPORTED");
    }

    @Test void identityDetectsSameSizeSameTimestampContentReplacement() throws Exception {
        Path executable = Files.writeString(temporary.resolve("fixture-binary-not-executed"), "before");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var identity = AgentCliProbe.inspect(executable);
        var timestamp = Files.getLastModifiedTime(executable);
        identity.verifyUnchanged();
        Files.writeString(executable, "after!");
        Files.setLastModifiedTime(executable, timestamp);
        assertThatThrownBy(identity::verifyUnchanged).hasMessage("AGENT_CLI_CHANGED");
    }

    @Test void rejectsNonExecutablesDirectoriesAndUntrustedWritePermissions() throws Exception {
        Path file = Files.writeString(temporary.resolve("not-executable"), "canary");
        assertThatThrownBy(() -> AgentCliProbe.inspect(file)).hasMessage("AGENT_CLI_UNSUPPORTED");
        assertThatThrownBy(() -> AgentCliProbe.inspect(temporary)).hasMessage("AGENT_CLI_UNSUPPORTED");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(() -> AgentCliProbe.inspect(file)).hasMessage("AGENT_CLI_UNSUPPORTED");
    }
}
