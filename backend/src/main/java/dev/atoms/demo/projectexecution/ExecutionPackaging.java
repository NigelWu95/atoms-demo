package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Private frontend resource copy, never a source rewrite or execution authorization. */
final class ExecutionPackaging {
    private static final long MAX_FILE = 8L << 20, MAX_TOTAL = 32L << 20;
    record Summary(int files, long bytes, String sha256) {}
    private record Stamp(Object key, boolean directory, long size, int mode, FileTime modified, String sha256) {}
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final Path input, resources;
    private final Object resourceKey;
    private final Map<String, Stamp> original, packaged;
    private final OwnedProcess.Preflight source;
    private final Summary summary;

    private ExecutionPackaging(ExecutionPaths paths, ExecutionPaths.Task task, Path input, Path resources,
                               Map<String, Stamp> original, Map<String, Stamp> packaged,
                               OwnedProcess.Preflight source) throws IOException {
        this.paths = paths; this.task = task; this.input = input; this.resources = resources;
        this.original = Map.copyOf(original); this.packaged = Map.copyOf(packaged); this.source = source;
        resourceKey = paths.privateDirectoryKey(task, resources);
        summary = summary(original);
        if (!summary.equals(summary(packaged))) throw invalid();
    }

    static ExecutionPackaging assemble(ExecutionPaths paths, ExecutionPaths.Task task, OwnedProcess.Budget budget,
                                       OwnedProcess.Preflight source) throws IOException {
        try {
            check(budget); Objects.requireNonNull(source).check();
            Path project = paths.project(task);
            paths.privateDirectoryKey(task, project.resolve("frontend"));
            paths.privateDirectoryKey(task, project.resolve("backend"));
            Path resources = paths.directory(task).resolve("resources");
            for (Path output : List.of(resources, project.resolve("backend/target")))
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("EXECUTION_OUTPUT_NOT_CLEAN");
            // No ambiguous resource-overlay precedence between authored static files and this frontend build.
            if (Files.exists(project.resolve("backend/src/main/resources/static"), LinkOption.NOFOLLOW_LINKS)) throw invalid();
            Path input = project.resolve("frontend/dist");
            Map<String, Stamp> original = scan(input, budget, false);
            paths.checkCapacity(budget);
            ExecutionRootLease.directory(resources);
            Path targetRoot = ExecutionRootLease.directory(resources.resolve("static"));
            for (var entry : original.entrySet()) {
                check(budget); paths.directory(task);
                if (entry.getKey().isEmpty()) continue;
                Path from = input.resolve(entry.getKey()), to = targetRoot.resolve(entry.getKey());
                ExecutionRootLease.noLinks(from); ExecutionRootLease.noLinks(to);
                if (entry.getValue().directory()) ExecutionRootLease.directory(to);
                else {
                    try (var in = Files.newInputStream(from, LinkOption.NOFOLLOW_LINKS);
                         var out = FileChannel.open(to,
                                 Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                                 PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                        transfer(in, out, entry.getValue().size(), budget);
                        out.force(true);
                    }
                }
            }
            var result = new ExecutionPackaging(paths, task, input, resources, original, scan(targetRoot, budget, true), source);
            result.verify(budget);
            return result;
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    Path directory() { return resources; }
    Summary summary() { return summary; }
    void verify(OwnedProcess.Budget budget) throws IOException {
        try {
            check(budget); source.check();
            if (!resourceKey.equals(paths.privateDirectoryKey(task, resources))) throw invalid();
            try (var children = Files.newDirectoryStream(resources)) {
                for (Path child : children) if (!child.equals(resources.resolve("static"))) throw invalid();
            }
            if (!original.equals(scan(input, budget, false)) || !packaged.equals(scan(resources.resolve("static"), budget, true)))
                throw invalid();
            paths.checkCapacity(budget); check(budget);
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    private static Map<String, Stamp> scan(Path root, OwnedProcess.Budget budget, boolean privateMode) throws IOException {
        ExecutionRootLease.noLinks(root);
        var result = new TreeMap<String, Stamp>();
        long[] total = {0};
        long uid = new com.sun.security.auth.module.UnixSystem().getUid();
        Files.walkFileTree(root, Set.of(), 14, new SimpleFileVisitor<>() {
            private void record(Path path, BasicFileAttributes before) throws IOException {
                check(budget); ExecutionRootLease.noLinks(path);
                String relative = root.relativize(path).toString();
                if (result.size() >= 1024 || path.getNameCount() - root.getNameCount() > 12
                        || before.fileKey() == null || (!before.isDirectory() && !before.isRegularFile())) throw invalid();
                if (!relative.isEmpty()) {
                    String name = path.getFileName().toString();
                    if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
                            || name.matches("(?i).*\\.(pem|key|p12|pfx|properties)")) throw invalid();
                }
                int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                if (((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != uid
                        || uid <= 0 || (mode & 07022) != 0
                        || privateMode && (mode & 0777) != (before.isDirectory() ? 0700 : 0600)) throw invalid();
                long size = before.isDirectory() ? 0 : before.size();
                String hash = "";
                if (before.isRegularFile()) {
                    if (size < 0 || size > MAX_FILE || (total[0] += size) > MAX_TOTAL
                            || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                        throw invalid();
                    var digest = sha256();
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        read(input, size, budget, digest);
                    }
                    hash = HexFormat.of().formatHex(digest.digest());
                }
                var after = ExecutionRootLease.attributes(path);
                if (!Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || before.size() != after.size()
                        || mode != ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue()) throw invalid();
                result.put(relative, new Stamp(before.fileKey(), before.isDirectory(), size, mode, before.lastModifiedTime(), hash));
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                record(path, attributes); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                record(path, attributes); return FileVisitResult.CONTINUE;
            }
        });
        Stamp index = result.get("index.html");
        if (!result.containsKey("") || !result.get("").directory() || index == null || index.directory() || index.size() == 0) throw invalid();
        return result;
    }

    private static void transfer(InputStream input, FileChannel output, long expected, OwnedProcess.Budget budget) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            check(budget);
            if ((total += count) > expected) throw invalid();
            var bytes = ByteBuffer.wrap(buffer, 0, count);
            while (bytes.hasRemaining()) { check(budget); output.write(bytes); }
        }
        if (total != expected) throw invalid();
    }
    private static void read(InputStream input, long expected, OwnedProcess.Budget budget, MessageDigest hash) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            check(budget);
            if ((total += count) > expected) throw invalid();
            hash.update(buffer, 0, count);
        }
        if (total != expected) throw invalid();
    }
    private static Summary summary(Map<String, Stamp> files) {
        var hash = sha256(); int count = 0; long bytes = 0;
        for (var entry : new TreeMap<>(files).entrySet()) {
            Stamp stamp = entry.getValue();
            if (stamp.directory()) continue;
            count++; bytes += stamp.size();
            hash.update((entry.getKey() + "\0" + stamp.size() + "\0" + stamp.sha256() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Summary(count, bytes, HexFormat.of().formatHex(hash.digest()));
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void check(OwnedProcess.Budget budget) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        if (Thread.currentThread().isInterrupted()) throw new IOException("EXECUTION_CANCELLED");
        var left = budget.remaining();
        if (left == null || left.isZero() || left.isNegative()) throw new IOException("EXECUTION_TIMEOUT");
    }
    private static IOException sanitized(Exception error) {
        return switch (Objects.toString(error.getMessage(), "")) {
            case "EXECUTION_TIMEOUT", "EXECUTION_CANCELLED", "EXECUTION_SOURCE_CHANGED", "EXECUTION_OUTPUT_NOT_CLEAN",
                 "EXECUTION_STORAGE_CAPACITY", "EXECUTION_STORAGE_UNAVAILABLE", "EXECUTION_TRANSACTION_ACTIVE" -> new IOException(error.getMessage());
            default -> invalid();
        };
    }
    private static IOException invalid() { return new IOException("EXECUTION_PACKAGING_INVALID"); }
}
