package dev.atoms.demo.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceGitProcessTest {
    @TempDir Path temporary;

    @Test void runsOnlyLocalPlumbingAndNeverInitializesTemplateHooks() throws Exception {
        Path root = temporary.toRealPath();
        var git = new SourceGitProcess(Path.of("/usr/bin/git"));
        assertThat(new String(git.run(root, List.of("--version"), new byte[0], 1024), StandardCharsets.UTF_8)).startsWith("git version");
        git.run(root, List.of("init", "--bare", "--template=", "repository.git"), new byte[0], 4096);
        assertThat(Files.isDirectory(root.resolve("repository.git"))).isTrue();
        assertThat(Files.exists(root.resolve("repository.git/hooks"))).isFalse();
        assertThatThrownBy(() -> git.run(root, List.of("clone", "https://example.invalid/repository"), new byte[0], 1024))
                .isInstanceOf(IOException.class);
    }

    @Test void clearsInheritedEnvironmentAndNeverPassesPersonalHome() throws Exception {
        Path root = temporary.toRealPath();
        Path executable = executable("#!/bin/sh\nprintf '%s:%s:%s' \"${HOME+present}\" \"${DEEPSEEK_API_KEY+present}\" \"${GIT_CONFIG_GLOBAL}\"\n");
        var git = new SourceGitProcess(executable);
        assertThat(new String(git.run(root, List.of("--version"), new byte[0], 1024), StandardCharsets.UTF_8)).isEqualTo("::/dev/null");
    }

    @Test void capsOutputWithoutReturningPartialSuccessfulData() throws Exception {
        var git = new SourceGitProcess(executable("#!/bin/sh\nprintf '1234567890'\n"));
        assertThatThrownBy(() -> git.run(temporary.toRealPath(), List.of("--version"), new byte[0], 8))
                .isInstanceOf(IOException.class).hasMessageContaining("GIT_OUTPUT_LIMIT");
    }

    @Test void timesOutAndWaitsForItsOwnProcessToExit() throws Exception {
        var observed = new java.util.concurrent.atomic.AtomicReference<ProcessHandle>();
        var git = new SourceGitProcess(executable("#!/bin/sh\nexec /bin/sleep 30\n"), Duration.ofMillis(300), observed::set);
        assertThatThrownBy(() -> git.run(temporary.toRealPath(), List.of("--version"), new byte[0], 1024))
                .isInstanceOf(IOException.class).hasMessageContaining("GIT_TIMEOUT");
        // A short deadline may expire before the script body starts. Observe the owned process,
        // not a PID file whose existence would introduce a scheduler-dependent race into this test.
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().isAlive()).isFalse();
    }

    @Test void errorsDoNotExposeRawDiagnosticContent() throws Exception {
        var git = new SourceGitProcess(executable("#!/bin/sh\nprintf 'canary-do-not-log' >&2\nexit 2\n"));
        assertThatThrownBy(() -> git.run(temporary.toRealPath(), List.of("--version"), new byte[0], 1024))
                .isInstanceOf(IOException.class).hasMessageContaining("GIT_FAILED").hasMessageNotContaining("canary-do-not-log");
    }

    @Test void interruptionCancelsOnlyTheObservedOwnedProcess() throws Exception {
        var observed = new java.util.concurrent.atomic.AtomicReference<ProcessHandle>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
        var ready = new java.util.concurrent.CountDownLatch(1);
        Path root = temporary.toRealPath();
        var git = new SourceGitProcess(executable("#!/bin/sh\nexec /bin/sleep 30\n"), Duration.ofSeconds(60), handle -> {
            observed.set(handle); ready.countDown();
        });
        Thread caller = new Thread(() -> {
            try { git.run(root, List.of("--version"), new byte[0], 1024); }
            catch (Exception error) { failure.set(error); }
        });
        caller.start();
        try {
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            caller.interrupt(); caller.join(7000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(observed.get().isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IOException.class).hasMessageContaining("GIT_CANCELLED");
        } finally { caller.interrupt(); caller.join(7000); }
    }

    private Path executable(String source) throws Exception {
        Path file = Files.writeString(temporary.toRealPath().resolve("git-fixture"), source);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
