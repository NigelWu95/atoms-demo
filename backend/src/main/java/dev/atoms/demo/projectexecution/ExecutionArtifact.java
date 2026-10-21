package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Byte sealing only. Caller must prove successful checks, stopped writers and current authorization first. */
final class ExecutionArtifact {
    static final long MAX_JAR_BYTES = 256L << 20;
    record Evidence(Map<ExecutionToolchain.Kind, ExecutionToolFiles.Summary> tools,
                    ExecutionNpmPolicy.Verified npm, ExecutionMavenPolicy.Verified maven,
                    ExecutionDiagnostics.Frontend frontend, ExecutionJavaReport.Summary javaTests,
                    ExecutionPackaging.Summary resources) {
        Evidence { tools = Map.copyOf(tools); }
    }
    record Summary(long jarBytes, String jarSha256, String manifestSha256) {}
    private record Stamp(Object key, long size, int mode, FileTime modified, String sha256) {}
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final Stamp jar, manifest;
    private final Summary summary;
    private final ExecutionBuild build;
    private final Evidence evidence;

    private ExecutionArtifact(ExecutionPaths paths, ExecutionPaths.Task task, Stamp jar, Stamp manifest,
                              ExecutionBuild build, Evidence evidence) {
        this.paths = paths; this.task = task; this.jar = jar; this.manifest = manifest;
        this.build = build; this.evidence = evidence;
        summary = new Summary(jar.size(), jar.sha256(), manifest.sha256());
    }

    static ExecutionArtifact seal(ExecutionPaths paths, ExecutionPaths.Task inputTask, ExecutionBuild build,
                                  Evidence evidence, OwnedProcess.Budget budget, OwnedProcess.Preflight preflight) throws IOException {
        try {
            check(budget); preflight.check();
            ExecutionArtifactManifest.validate(build, evidence);
            if (!paths.directory(inputTask).getFileName().toString().equals(build.id().toString())) throw invalid();
            Path backend = paths.project(inputTask).resolve("backend");
            paths.privateDirectoryKey(inputTask, backend);
            Path target = backend.resolve("target"), input = target.resolve("app.jar");
            Object targetKey = outputDirectory(target);
            Stamp original = scan(input, MAX_JAR_BYTES, false, budget);
            check(budget);
            ExecutionPaths.Task task;
            try { task = paths.createArtifact(build.id(), budget); }
            catch (FileAlreadyExistsException exists) { throw new IOException("EXECUTION_OUTPUT_NOT_CLEAN"); }
            Path directory = paths.directory(task), jar = directory.resolve("app.jar");
            try (var in = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
                write(jar, in, original.size(), budget);
            }
            // Re-read both sides: same-length changes and replacing an inode with identical bytes also fail.
            Stamp copied = scan(jar, MAX_JAR_BYTES, true, budget);
            if (copied.size() != original.size() || !copied.sha256().equals(original.sha256())
                    || !original.equals(scan(input, MAX_JAR_BYTES, false, budget))
                    || !targetKey.equals(outputDirectory(target))) throw invalid();
            check(budget); preflight.check();
            byte[] manifest = ExecutionArtifactManifest.bytes(build, evidence, copied.size(), copied.sha256());
            Path manifestPath = directory.resolve("manifest.json");
            write(manifestPath, new ByteArrayInputStream(manifest), manifest.length, budget);
            var result = new ExecutionArtifact(paths, task, copied, scan(manifestPath, 16384, true, budget), build, evidence);
            if (!result.manifest.sha256().equals(hash(manifest))) throw invalid();
            result.verify(budget);
            check(budget); preflight.check();
            if (!targetKey.equals(outputDirectory(target)) || !original.equals(scan(input, MAX_JAR_BYTES, false, budget)))
                throw invalid();
            paths.privateDirectoryKey(inputTask, backend);
            result.verify(budget);
            return result;
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    Path jar() throws IOException { return paths.directory(task).resolve("app.jar"); }
    Summary summary() { return summary; }
    /** Immutable in-memory binding, not a capability reconstructed from caller-supplied hashes or paths. */
    Evidence evidenceFor(ExecutionBuild expected) throws IOException {
        if (expected == null || !build.id().equals(expected.id()) || !build.projectId().equals(expected.projectId())
                || !build.ownerId().equals(expected.ownerId()) || !build.sourceVersionId().equals(expected.sourceVersionId())
                || !build.treeSha256().equals(expected.treeSha256()) || !build.keySha256().equals(expected.keySha256())
                || !build.requestSha256().equals(expected.requestSha256())) throw invalid();
        return evidence;
    }
    void verify(OwnedProcess.Budget budget) throws IOException {
        try {
            check(budget);
            Path directory = paths.directory(task);
            try (var children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    check(budget);
                    if (!Set.of(".atoms-execution-task", "app.jar", "manifest.json").contains(child.getFileName().toString()))
                        throw invalid();
                }
            }
            if (!jar.equals(scan(directory.resolve("app.jar"), MAX_JAR_BYTES, true, budget))
                    || !manifest.equals(scan(directory.resolve("manifest.json"), 16384, true, budget))) throw invalid();
            paths.directory(task); paths.checkCapacity(budget); check(budget);
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    private static Object outputDirectory(Path path) throws IOException {
        ExecutionRootLease.noLinks(path);
        var attrs = ExecutionRootLease.attributes(path);
        if (!attrs.isDirectory() || attrs.fileKey() == null) throw invalid();
        mode(path, false, true);
        return attrs.fileKey();
    }
    private static Stamp scan(Path path, long limit, boolean privateMode, OwnedProcess.Budget budget) throws IOException {
        check(budget); ExecutionRootLease.noLinks(path);
        var before = ExecutionRootLease.attributes(path);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() <= 0 || before.size() > limit) throw invalid();
        int mode = mode(path, privateMode, false);
        var digest = sha256(); long total = 0; byte[] buffer = new byte[65536]; int count;
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            while ((count = in.read(buffer)) != -1) {
                check(budget);
                if ((total += count) > before.size()) throw invalid();
                digest.update(buffer, 0, count);
            }
        }
        var after = ExecutionRootLease.attributes(path);
        if (total != before.size() || !before.fileKey().equals(after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || mode != mode(path, privateMode, false)) throw invalid();
        check(budget);
        return new Stamp(before.fileKey(), total, mode, before.lastModifiedTime(), HexFormat.of().formatHex(digest.digest()));
    }
    private static int mode(Path path, boolean privateMode, boolean directory) throws IOException {
        long uid = new com.sun.security.auth.module.UnixSystem().getUid();
        int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
        if (uid <= 0 || ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != uid
                || (mode & 07022) != 0 || privateMode && (mode & 0777) != 0600
                || !directory && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
            throw invalid();
        return mode;
    }
    private static void write(Path file, InputStream input, long expected, OwnedProcess.Budget budget) throws IOException {
        check(budget); ExecutionRootLease.noLinks(file);
        // CREATE_NEW is atomic; force persists file data/metadata, not a cross-file/DB transaction.
        // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html
        try (var out = FileChannel.open(file,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            byte[] buffer = new byte[65536]; long total = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                check(budget);
                if ((total += count) > expected) throw invalid();
                var bytes = ByteBuffer.wrap(buffer, 0, count);
                while (bytes.hasRemaining()) { check(budget); out.write(bytes); }
            }
            if (total != expected) throw invalid();
            check(budget); out.force(true);
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
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
    private static IOException invalid() { return new IOException("EXECUTION_ARTIFACT_INVALID"); }
}
