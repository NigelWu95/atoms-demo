package dev.atoms.demo.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local plumbing only. Callers supply validated object ids/UUID refs, never user command lines. */
final class SourceGitProcess {
    private static final Set<String> COMMANDS = Set.of("--version", "init", "hash-object", "mktree", "commit-tree",
            "update-ref", "cat-file", "ls-tree", "rev-parse", "diff", "show-ref", "for-each-ref");
    private final Path executable;
    private final Duration timeout;
    private final java.util.function.Consumer<ProcessHandle> started;
    private final SourceDeadline deadline;

    SourceGitProcess(Path executable) { this(executable, Duration.ofSeconds(60)); }
    SourceGitProcess(Path executable, Duration timeout) { this(executable, timeout, handle -> {}); }
    SourceGitProcess(Path executable, Duration timeout, java.util.function.Consumer<ProcessHandle> started) {
        this(executable, timeout, started, null);
    }
    SourceGitProcess(Path executable, Duration timeout, java.util.function.Consumer<ProcessHandle> started, SourceDeadline deadline) {
        this.executable = executable;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid Git timeout");
        this.timeout = timeout;
        this.started = java.util.Objects.requireNonNull(started);
        this.deadline = deadline;
    }

    byte[] run(Path directory, List<String> arguments, byte[] input, int outputLimit) throws IOException {
        if (arguments.isEmpty() || !COMMANDS.contains(arguments.get(0)) || arguments.size() > 32
                || arguments.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0 || value.length() > 1024)
                || input.length > (16 << 20) || outputLimit < 1 || outputLimit > (32 << 20)) throw failure("GIT_COMMAND_REJECTED");
        if (!executable.isAbsolute() || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(executable) || !directory.isAbsolute()
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw failure("GIT_UNAVAILABLE");
        ProjectSourcePaths.noLinks(directory);
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(List.of("--no-pager", "--no-optional-locks"));
        for (String setting : List.of("core.hooksPath=/dev/null", "core.attributesFile=/dev/null", "core.autocrlf=false",
                "commit.gpgSign=false", "tag.gpgSign=false", "gc.auto=0", "maintenance.auto=false", "protocol.allow=never")) {
            command.add("-c"); command.add(setting);
        }
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        // No personal HOME, platform env, auth helpers, alternate objects or inherited Git overrides.
        // https://git-scm.com/docs/git/2.39.0#_environment_variables
        var environment = builder.environment(); environment.clear();
        environment.putAll(java.util.Map.of("PATH", "/usr/bin:/bin", "LANG", "C", "LC_ALL", "C", "TZ", "UTC",
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_SYSTEM", "/dev/null", "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_ATTR_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0", "GIT_NO_REPLACE_OBJECTS", "1"));
        environment.put("GIT_AUTHOR_NAME", "Atoms Demo"); environment.put("GIT_COMMITTER_NAME", "Atoms Demo");
        environment.put("GIT_AUTHOR_EMAIL", "source@atoms.invalid"); environment.put("GIT_COMMITTER_EMAIL", "source@atoms.invalid");
        Duration remaining = deadline == null ? timeout : deadline.remaining(timeout);
        Process process = builder.start();
        var streams = Executors.newFixedThreadPool(3, task -> new Thread(task, "atoms-source-git-io"));
        try {
            started.accept(process.toHandle());
            Future<byte[]> stdout = streams.submit(() -> read(process.getInputStream(), outputLimit, process));
            Future<byte[]> stderr = streams.submit(() -> read(process.getErrorStream(), 64 << 10, process));
            Future<?> stdin = streams.submit(() -> {
                try (var output = process.getOutputStream()) { output.write(input); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            if (!process.waitFor(remaining.toNanos(), TimeUnit.NANOSECONDS)) throw failure("GIT_TIMEOUT");
            byte[] output = result(stdout); result(stderr);
            if (process.exitValue() != 0) throw failure("GIT_FAILED");
            result(stdin);
            return output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure("GIT_CANCELLED");
        } finally {
            try { stop(process); }
            finally { streams.shutdownNow(); }
        }
    }

    private static byte[] read(InputStream input, int limit, Process process) throws IOException {
        try (input; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > limit) { process.destroyForcibly(); throw failure("GIT_OUTPUT_LIMIT"); }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static <T> T result(Future<T> future) throws IOException, InterruptedException {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof GitFailure failure) throw failure;
            throw failure("GIT_IO_FAILED");
        } catch (TimeoutException error) { throw failure("GIT_IO_UNSETTLED"); }
    }

    private static void stop(Process process) throws IOException {
        stop(process, owned -> owned.descendants().toList());
    }

    static void stop(Process process, java.util.function.Function<Process, List<ProcessHandle>> descendants) throws IOException {
        boolean interrupted = Thread.interrupted();
        StopDetail detail = null;
        StopStage stage = StopStage.ENUMERATE;
        try {
            // Only handles observed beneath this live owned process; never scan/kill by cached PID or name.
            List<ProcessHandle> children = List.of();
            try { children = descendants.apply(process); }
            catch (RuntimeException error) { detail = first(detail, stage, error, interrupted); }
            stage = StopStage.CHILD_STOP;
            for (var child : children) {
                try { if (child.isAlive()) child.destroyForcibly(); }
                catch (RuntimeException error) { detail = first(detail, stage, error, interrupted); }
            }
            stage = StopStage.PARENT_STOP;
            try { if (process.isAlive()) process.destroyForcibly(); }
            catch (RuntimeException error) { detail = first(detail, stage, error, interrupted); }
            stage = StopStage.PARENT_WAIT;
            if (!process.waitFor(5, TimeUnit.SECONDS))
                throw new GitFailure(first(detail, stage, new TimeoutException(), interrupted));
            stage = StopStage.CHILD_WAIT;
            for (var child : children) {
                try { child.onExit().get(5, TimeUnit.SECONDS); }
                catch (ExecutionException | TimeoutException | RuntimeException error) { detail = first(detail, stage, error, interrupted); }
            }
            if (detail != null) throw new GitFailure(detail);
        } catch (InterruptedException error) {
            interrupted = true;
            throw new GitFailure(first(detail, stage, error, true));
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    enum StopStage { NONE, ENUMERATE, CHILD_STOP, PARENT_STOP, PARENT_WAIT, CHILD_WAIT }
    enum StopKind { NONE, ACCESS_DENIED, UNSUPPORTED, INVALID_STATE, NATIVE_ENUMERATION, RUNTIME_FAILURE, TIMEOUT, WAIT_FAILED, INTERRUPTED }
    record StopDetail(StopStage stage, StopKind kind, boolean interrupted) {
        static final StopDetail NONE = new StopDetail(StopStage.NONE, StopKind.NONE, false);
    }

    private static StopDetail first(StopDetail existing, StopStage stage, Exception error, boolean interrupted) {
        // Only fixed categories survive; neither the exception nor arbitrary class/message/stack text is retained.
        return existing != null ? existing : new StopDetail(stage, stopKind(error), interrupted || Thread.currentThread().isInterrupted());
    }

    private static StopKind stopKind(Exception error) {
        if (error instanceof InterruptedException) return StopKind.INTERRUPTED;
        if (error instanceof TimeoutException) return StopKind.TIMEOUT;
        if (error instanceof ExecutionException) return StopKind.WAIT_FAILED;
        if (error instanceof SecurityException) return StopKind.ACCESS_DENIED;
        if (error instanceof UnsupportedOperationException) return StopKind.UNSUPPORTED;
        if (error instanceof IllegalStateException) return StopKind.INVALID_STATE;
        if (error.getClass() == RuntimeException.class) {
            var frames = error.getStackTrace();
            for (int i = 0; i < Math.min(16, frames.length); i++) {
                var frame = frames[i];
                if (frame.isNativeMethod() && frame.getClassName().equals("java.lang.ProcessHandleImpl")
                        && frame.getMethodName().equals("getProcessPids0")) return StopKind.NATIVE_ENUMERATION;
            }
        }
        return StopKind.RUNTIME_FAILURE;
    }

    private static GitFailure failure(String code) { return new GitFailure(code); }
    static final class GitFailure extends IOException {
        private final StopDetail stopDetail;
        GitFailure(String code) { super(code); stopDetail = StopDetail.NONE; }
        private GitFailure(StopDetail detail) { super("GIT_PROCESS_UNSETTLED"); stopDetail = detail; }
        StopDetail stopDetail() { return stopDetail; }
    }
}
