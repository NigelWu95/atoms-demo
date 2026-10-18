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

    SourceGitProcess(Path executable) { this(executable, Duration.ofSeconds(60)); }
    SourceGitProcess(Path executable, Duration timeout) { this(executable, timeout, handle -> {}); }
    SourceGitProcess(Path executable, Duration timeout, java.util.function.Consumer<ProcessHandle> started) {
        this.executable = executable;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid Git timeout");
        this.timeout = timeout;
        this.started = java.util.Objects.requireNonNull(started);
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
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw failure("GIT_TIMEOUT");
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
        boolean interrupted = Thread.interrupted();
        try {
            // Only handles observed beneath this live owned process; never scan/kill by cached PID or name.
            var children = process.descendants().toList();
            for (var child : children) if (child.isAlive()) child.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw failure("GIT_PROCESS_UNSETTLED");
            for (var child : children) {
                try { child.onExit().get(5, TimeUnit.SECONDS); }
                catch (ExecutionException | TimeoutException error) { throw failure("GIT_PROCESS_UNSETTLED"); }
            }
        } catch (InterruptedException error) {
            interrupted = true;
            throw failure("GIT_PROCESS_UNSETTLED");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static GitFailure failure(String code) { return new GitFailure(code); }
    static final class GitFailure extends IOException {
        GitFailure(String code) { super(code); }
    }
}
