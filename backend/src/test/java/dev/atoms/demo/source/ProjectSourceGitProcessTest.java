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

    @Test void emptyInputStillSucceedsWhenChildExitsBeforeIoWorkersStart() throws Exception {
        // Widen the real scheduling window deterministically; never delay production or retry a failure.
        var git = new SourceGitProcess(executable("#!/bin/sh\nprintf 'completed'\n"), Duration.ofSeconds(5), handle -> {
            try { handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception error) { throw new AssertionError("Fixture did not exit", error); }
        });
        assertThat(new String(git.run(temporary.toRealPath(), List.of("--version"), new byte[0], 1024),
                StandardCharsets.UTF_8)).isEqualTo("completed");
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

    @Test void expiredOperationDoesNotStartAnotherGitProcess() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var deadline = new SourceDeadline(Duration.ofSeconds(300), clock::get);
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        var git = new SourceGitProcess(Path.of("/usr/bin/git"), Duration.ofSeconds(60), handle -> started.set(true), deadline);
        clock.set(Duration.ofSeconds(301).toNanos());
        assertThatThrownBy(() -> git.run(temporary.toRealPath(), List.of("--version"), new byte[0], 1024))
                .isInstanceOf(SourceDeadline.Expired.class);
        assertThat(started).isFalse();
    }

    @Test void failedChildEnumerationStillStopsTheOwnedParentAndReportsUncertainty() throws Exception {
        var builder = new ProcessBuilder("/bin/sleep", "30");
        builder.environment().clear();
        var process = builder.start();
        try {
            assertThatThrownBy(() -> SourceGitProcess.stop(process, ignored -> { throw new IllegalStateException("native enumeration failed"); }))
                    .isInstanceOf(IOException.class).hasMessage("GIT_PROCESS_UNSETTLED");
            assertThat(process.isAlive()).isFalse();
        } finally { process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test void failedChildInspectionStillStopsTheOwnedParent() throws Exception {
        var child = org.mockito.Mockito.mock(ProcessHandle.class);
        org.mockito.Mockito.when(child.isAlive()).thenThrow(new IllegalStateException("native child lookup failed"));
        var builder = new ProcessBuilder("/bin/sleep", "30");
        builder.environment().clear();
        var process = builder.start();
        try {
            assertThatThrownBy(() -> SourceGitProcess.stop(process, ignored -> List.of(child)))
                    .isInstanceOf(IOException.class).hasMessage("GIT_PROCESS_UNSETTLED");
            assertThat(process.isAlive()).isFalse();
        } finally { process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
    }
}
