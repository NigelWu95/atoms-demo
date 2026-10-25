package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed local launcher only. Binding metadata is not a grant to attach credentials or start a process. */
final class ExecutionRunCommand {
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final ExecutionArtifact artifact;
    private final ExecutionToolchain tools;
    private final Path directory, config;
    private final Map<Path, Object> directories;
    private final Object configKey;
    private final byte[] contents;
    private final List<String> command;
    private final Map<String, String> environment;

    private ExecutionRunCommand(ExecutionPaths paths, ExecutionPaths.Task task, ExecutionArtifact artifact,
            ExecutionToolchain tools, byte[] contents) throws IOException {
        this.paths = paths; this.task = task; this.artifact = artifact; this.tools = tools; this.contents = contents.clone();
        directory = paths.directory(task); config = directory.resolve("config/application.properties");
        Path home = directory.resolve("home"), temporary = directory.resolve("tmp"), javaHome = tools.javaHome();
        for (Path path : List.of(directory, artifact.jar(), javaHome)) requirePath(path);
        var identities = new HashMap<Path, Object>();
        for (Path path : List.of(home, temporary, config.getParent())) identities.put(path, paths.privateDirectoryKey(task, path));
        directories = Map.copyOf(identities); configKey = paths.privateFileKey(task, config, contents.length);
        command = List.of(javaHome.resolve("bin/java").toString(), "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary,
                "-cp", artifact.jar().toString(), "org.springframework.boot.loader.launch.JarLauncher",
                "--spring.config.location=" + config.toUri().toASCIIString());
        environment = Map.of("PATH", javaHome.resolve("bin") + ":/usr/bin:/bin", "JAVA_HOME", javaHome.toString(),
                "HOME", home.toString(), "TMPDIR", temporary.toString(), "TMP", temporary.toString(), "TEMP", temporary.toString(),
                "LANG", "C.UTF-8", "LC_ALL", "C.UTF-8", "TZ", "UTC",
                "JDK_JAVA_OPTIONS", "\"-Duser.home=" + home + "\" \"-Djava.io.tmpdir=" + temporary + "\"");
    }

    static ExecutionRunCommand prepare(ExecutionPaths paths, UUID run, UUID execution, ExecutionToolchain tools,
            ExecutionArtifact artifact, ExecutionBuild build, ExecutionBindingRepository.Target binding,
            OwnedProcess.Budget budget) throws IOException {
        try {
            check(budget);
            if (run == null || execution == null || build == null || binding == null || !"SUCCEEDED".equals(build.status())
                    || !build.projectId().equals(binding.project())) throw invalid();
            var evidence = artifact.evidenceFor(build);
            if (!tools.identity(ExecutionToolchain.Kind.JAVA).equals(evidence.tools().get(ExecutionToolchain.Kind.JAVA))) throw invalid();
            tools.verifyJava(budget); artifact.verify(budget);
            var task = paths.createRun(run, budget); Path directory = paths.directory(task);
            for (String name : List.of("home", "tmp", "config")) { check(budget); ExecutionRootLease.directory(directory.resolve(name)); }
            byte[] contents = properties(run, execution, binding.port()).getBytes(StandardCharsets.US_ASCII);
            Path config = directory.resolve("config/application.properties");
            try (var file = FileChannel.open(config, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                var buffer = ByteBuffer.wrap(contents);
                while (buffer.hasRemaining()) { check(budget); file.write(buffer); }
                file.force(true);
            }
            var result = new ExecutionRunCommand(paths, task, artifact, tools, contents);
            result.verify(budget); return result;
        } catch (FileAlreadyExistsException exists) { throw new IOException("RUN_WORKSPACE_NOT_CLEAN"); }
        catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    ProcessBuilder builder() {
        var result = new ProcessBuilder(new ArrayList<>(command)).directory(directory.toFile());
        result.environment().clear(); result.environment().putAll(environment); return result;
    }

    void verify(OwnedProcess.Budget budget) throws IOException {
        try {
            check(budget); paths.directory(task);
            for (var entry : directories.entrySet())
                if (!entry.getValue().equals(paths.privateDirectoryKey(task, entry.getKey()))) throw invalid();
            members(directory, Set.of(".atoms-execution-task", "home", "tmp", "config"), budget);
            members(config.getParent(), Set.of("application.properties"), budget);
            if (!configKey.equals(paths.privateFileKey(task, config, contents.length))) throw invalid();
            try (var input = Files.newInputStream(config, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(contents, input.readNBytes(contents.length + 1))) throw invalid();
            }
            if (!configKey.equals(paths.privateFileKey(task, config, contents.length))) throw invalid();
            tools.verifyJava(budget); artifact.verify(budget); paths.directory(task); check(budget);
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    private static String properties(UUID run, UUID execution, int port) {
        return "server.port=" + port + "\natoms.runtime.run-id=" + run + "\natoms.runtime.execution-id=" + execution + "\n" + """
                server.address=127.0.0.1
                server.error.include-message=never
                server.error.include-stacktrace=never
                server.error.include-binding-errors=never
                spring.application.name=generated-app
                spring.datasource.url=${APP_DB_URL}
                spring.datasource.username=${APP_DB_USERNAME}
                spring.datasource.password=${APP_DB_PASSWORD}
                spring.datasource.hikari.maximum-pool-size=4
                spring.datasource.hikari.connection-timeout=5000
                spring.flyway.enabled=true
                spring.flyway.locations=classpath:db/migration
                spring.flyway.table=flyway_schema_history
                spring.flyway.default-schema=generated_app
                spring.flyway.schemas=generated_app
                spring.flyway.create-schemas=false
                spring.flyway.clean-disabled=true
                spring.flyway.baseline-on-migrate=false
                spring.flyway.validate-on-migrate=true
                spring.flyway.validate-migration-naming=true
                spring.flyway.out-of-order=false
                spring.flyway.placeholder-replacement=false
                spring.sql.init.mode=never
                """;
    }
    private static void members(Path directory, Set<String> expected, OwnedProcess.Budget budget) throws IOException {
        try (var entries = Files.newDirectoryStream(directory)) {
            var actual = new HashSet<String>();
            for (Path path : entries) {
                check(budget); String name = path.getFileName().toString();
                if (!expected.contains(name)) throw invalid(); actual.add(name);
            }
            if (!actual.equals(expected)) throw invalid();
        }
    }
    private static void requirePath(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)
                || path.toString().chars().anyMatch(c -> c < 32 || c == 127 || c == ':' || c == '"' || c == '\\')) throw invalid();
    }
    private static void check(OwnedProcess.Budget budget) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        if (Thread.currentThread().isInterrupted()) throw new IOException("EXECUTION_CANCELLED");
        var left = budget.remaining();
        if (left == null || left.isNegative() || left.isZero()) throw new IOException("EXECUTION_TIMEOUT");
    }
    private static IOException sanitized(Exception error) {
        String code = error.getMessage();
        return new IOException(Set.of("EXECUTION_TIMEOUT", "EXECUTION_CANCELLED", "EXECUTION_TRANSACTION_ACTIVE",
                "EXECUTION_TOOL_CHANGED", "EXECUTION_STORAGE_UNAVAILABLE", "EXECUTION_STORAGE_CAPACITY",
                "EXECUTION_ARTIFACT_INVALID").contains(code == null ? "" : code) ? code : "RUN_COMMAND_INVALID");
    }
    private static IOException invalid() { return new IOException("RUN_COMMAND_INVALID"); }
}
