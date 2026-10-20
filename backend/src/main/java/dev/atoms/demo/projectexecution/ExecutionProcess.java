package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Internal fixed-tool execution adapter. Tool approval and directory identity belong to preflight. */
final class ExecutionProcess {
    static final long STREAM_MAX_BYTES = 8L << 20;
    record Output(long stdoutBytes, long stderrBytes) {}
    private final OwnedProcess owner = new OwnedProcess();

    OwnedProcess.Result<Output> run(Invocation invocation, Deadline deadline, BooleanSupplier cancelled,
                                    UUID task, UUID execution, OwnedProcess.Preflight preflight) throws IOException {
        long[] stderrBytes = {0};
        var result = owner.runCommand(invocation.builder(), deadline::remaining, cancelled, task, execution,
                ExecutionProcess::drain, stream -> { stderrBytes[0] = drain(stream); return null; }, preflight);
        // Both futures have completed before runCommand returns; no raw output escapes this adapter.
        return new OwnedProcess.Result<>(new Output(result.summary(), stderrBytes[0]), result.stopped());
    }

    private static long drain(InputStream stream) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0; int count;
        while ((count = stream.read(buffer)) != -1) {
            total += count;
            if (total > STREAM_MAX_BYTES) throw new IOException("EXECUTION_OUTPUT_LIMIT");
        }
        return total;
    }

    static final class Deadline implements OwnedProcess.Budget {
        private final LongSupplier clock;
        private final long started, budget;
        Deadline(Duration timeout) { this(timeout, System::nanoTime); }
        Deadline(Duration timeout, LongSupplier clock) {
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(1800)) > 0)
                throw new IllegalArgumentException("EXECUTION_INVALID_DEADLINE");
            this.clock = java.util.Objects.requireNonNull(clock);
            started = clock.getAsLong(); budget = timeout.toNanos();
        }
        @Override public Duration remaining() throws IOException {
            long elapsed = clock.getAsLong() - started;
            if (elapsed < 0 || elapsed >= budget) throw new IOException("EXECUTION_TIMEOUT");
            return Duration.ofNanos(budget - elapsed);
        }
    }

    /** Not a public request DTO; callers supply approved absolute tools and platform-owned directories. */
    static final class Invocation {
        private final List<String> command;
        private final Path directory;
        private final Map<String, String> environment;

        Invocation(Path executable, List<String> arguments, Path directory, Path privateHome, Path temporary,
                   Path javaHome, Path nodeBin) throws IOException {
            for (Path path : new Path[]{executable, directory, privateHome, temporary, javaHome, nodeBin})
                requirePath(path);
            if (arguments == null || arguments.size() > 128) throw rejected();
            long size = 0;
            for (String arg : arguments) {
                if (arg == null || arg.length() > 16384 || arg.chars().anyMatch(c -> c < 32 || c == 127)) throw rejected();
                size += arg.length();
            }
            if (size > (128 << 10)) throw rejected();
            var argv = new ArrayList<String>();
            argv.add(executable.toString()); argv.addAll(arguments);
            command = List.copyOf(argv); this.directory = directory;
            environment = Map.ofEntries(Map.entry("PATH", nodeBin + ":" + javaHome.resolve("bin") + ":/usr/bin:/bin"),
                    Map.entry("HOME", privateHome.toString()), Map.entry("JAVA_HOME", javaHome.toString()),
                    Map.entry("TMPDIR", temporary.toString()), Map.entry("TMP", temporary.toString()),
                    Map.entry("TEMP", temporary.toString()), Map.entry("LANG", "C.UTF-8"), Map.entry("LC_ALL", "C.UTF-8"),
                    Map.entry("TZ", "UTC"), Map.entry("CI", "true"), Map.entry("NO_COLOR", "1"), Map.entry("TERM", "dumb"),
                    Map.entry("MAVEN_SKIP_RC", "true"), Map.entry("MAVEN_BASEDIR", directory.toString()),
                    Map.entry("npm_config_userconfig", privateHome.resolve("npmrc").toString()),
                    Map.entry("npm_config_globalconfig", privateHome.resolve("npm-globalrc").toString()),
                    Map.entry("npm_config_cache", privateHome.resolve("npm-cache").toString()));
        }

        ProcessBuilder builder() {
            var builder = new ProcessBuilder(new ArrayList<>(command)).directory(directory.toFile());
            // Explicit env per Java 17 ProcessBuilder; no inherited credentials, proxy or JVM/Node options.
            builder.environment().clear();
            builder.environment().putAll(environment);
            return builder;
        }

        private static void requirePath(Path path) throws IOException {
            if (path == null || !path.isAbsolute() || !path.normalize().equals(path)
                    || path.toString().chars().anyMatch(c -> c < 32 || c == 127 || c == ':')) throw rejected();
        }
        private static IOException rejected() { return new IOException("EXECUTION_INVOCATION_REJECTED"); }
    }
}
