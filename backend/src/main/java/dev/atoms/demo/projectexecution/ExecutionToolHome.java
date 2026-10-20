package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Task-private package-manager settings; consistency checks, not an OS sandbox. */
final class ExecutionToolHome {
    // https://maven.apache.org/settings.html ; https://maven.apache.org/ref/3.9.11/maven-embedder/cli.html
    // https://github.com/npm/cli/blob/v11.19.0/docs/lib/content/configuring-npm/npmrc.md
    private static final Map<String, String> CONFIG = Map.of(
            "npmrc", """
                    registry=https://registry.npmjs.org/
                    ignore-scripts=true
                    strict-ssl=true
                    audit=false
                    fund=false
                    update-notifier=false
                    logs-max=0
                    """,
            "npm-globalrc", "",
            "settings.xml", """
                    <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                      <interactiveMode>false</interactiveMode>
                      <mirrors><mirror><id>execution-central</id><mirrorOf>*</mirrorOf>
                        <url>https://repo.maven.apache.org/maven2</url>
                      </mirror></mirrors>
                    </settings>
                    """,
            "global-settings.xml", "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"/>\n",
            "toolchains.xml", "<toolchains xmlns=\"http://maven.apache.org/TOOLCHAINS/1.1.0\"/>\n");
    private static final List<String> DIRECTORIES = List.of("tmp", "npm-cache", "maven-cache");
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final Path home;
    private final Map<Path, Object> directories = new LinkedHashMap<>(), files = new LinkedHashMap<>();

    private ExecutionToolHome(ExecutionPaths paths, ExecutionPaths.Task task, Path home) {
        this.paths = paths; this.task = task; this.home = home;
    }

    static ExecutionToolHome create(ExecutionPaths paths, ExecutionPaths.Task task, OwnedProcess.Budget budget) throws IOException {
        checkBudget(budget);
        try {
            paths.project(task); // Build tasks only; a path alone cannot authorize configuration allocation.
            paths.checkCapacity(budget);
            Path home = ExecutionRootLease.directory(paths.directory(task).resolve("tool-home"));
            var config = new ExecutionToolHome(paths, task, home);
            config.directories.put(home, paths.privateDirectoryKey(task, home));
            for (String name : DIRECTORIES) {
                Path directory = ExecutionRootLease.directory(home.resolve(name));
                config.directories.put(directory, paths.privateDirectoryKey(task, directory));
            }
            for (var entry : CONFIG.entrySet()) {
                checkBudget(budget);
                byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                Path file = home.resolve(entry.getKey());
                try (var channel = FileChannel.open(file,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                    var bytes = ByteBuffer.wrap(content);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                config.files.put(file, paths.privateFileKey(task, file, content.length));
            }
            config.verify(budget);
            return config;
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    void verify(OwnedProcess.Budget budget) throws IOException {
        checkBudget(budget);
        try {
            paths.project(task);
            for (var entry : directories.entrySet())
                if (!entry.getValue().equals(paths.privateDirectoryKey(task, entry.getKey())))
                    throw ExecutionRootLease.unavailable();
            // Unexpected home files could introduce implicit npm/JVM/Maven configuration.
            try (var children = Files.newDirectoryStream(home)) {
                for (Path child : children) {
                    checkBudget(budget);
                    if (!directories.containsKey(child) && !files.containsKey(child)) throw ExecutionRootLease.unavailable();
                }
            }
            for (var entry : files.entrySet()) {
                checkBudget(budget);
                byte[] expected = CONFIG.get(entry.getKey().getFileName().toString()).getBytes(StandardCharsets.UTF_8);
                if (!entry.getValue().equals(paths.privateFileKey(task, entry.getKey(), expected.length)))
                    throw ExecutionRootLease.unavailable();
                try (var stream = Files.newInputStream(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) {
                    if (!Arrays.equals(expected, stream.readNBytes(expected.length + 1))) throw ExecutionRootLease.unavailable();
                }
                if (!entry.getValue().equals(paths.privateFileKey(task, entry.getKey(), expected.length)))
                    throw ExecutionRootLease.unavailable();
            }
            paths.checkCapacity(budget);
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    Path home() { return home; }
    Path temporary() { return home.resolve("tmp"); }
    Path mavenRepository() { return home.resolve("maven-cache"); }

    /** Fixed prefix only. Tool identity, lifecycle goals and preflight are applied by the toolchain. */
    List<String> mavenOptions() {
        return List.of("-B", "-C", "-N", "-ntp", "-itr",
                "-s", home.resolve("settings.xml").toString(), "-gs", home.resolve("global-settings.xml").toString(),
                "-t", home.resolve("toolchains.xml").toString(), "-gt", home.resolve("toolchains.xml").toString(),
                "-Dmaven.repo.local=" + mavenRepository(), "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary());
    }

    private static void checkBudget(OwnedProcess.Budget budget) throws IOException {
        try {
            var left = budget.remaining();
            if (left == null || left.isZero() || left.isNegative()) throw new IOException();
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_TIMEOUT"); }
    }
    private static IOException sanitized(Exception error) {
        if (error instanceof IOException && ("EXECUTION_TIMEOUT".equals(error.getMessage())
                || "EXECUTION_STORAGE_CAPACITY".equals(error.getMessage())))
            return new IOException(error.getMessage());
        return ExecutionRootLease.unavailable();
    }
}
