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

    @Test void expiredTaskCannotLaunchEvenAVersionProbe() throws Exception {
        Path binary = Files.writeString(temporary.resolve("not-executed"), "not a program");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        assertThatThrownBy(() -> AgentCliProbe.verify(binary, temporary, temporary,
                () -> { throw new java.io.IOException("AGENT_TIMEOUT"); })).hasMessage("AGENT_TIMEOUT");
    }

    @Test void probeCannotHangOnAChildHoldingItsOutputPipe() throws Exception {
        var parent = temporary.resolve("parent.pid"); var child = temporary.resolve("child.pid");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), AgentChildFixture.class.getName(),
                "child-pipe", parent.toString(), child.toString()).directory(temporary.toFile());
        command.environment().clear();
        long start = System.nanoTime(); var budget = new AgentDeadline(java.time.Duration.ofSeconds(3));
        assertThatThrownBy(() -> AgentCliProbe.execute(command, budget::remaining)).isInstanceOf(java.io.IOException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(14));
        for (var file : new Path[] {parent, child}) {
            assertThat(file).isRegularFile();
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(file))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

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
