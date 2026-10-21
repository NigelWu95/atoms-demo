package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** Fresh directory capability; callers must prove Maven and its children stopped before reading. */
final class ExecutionJavaReports {
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final Path target, directory;
    private final Object targetKey, directoryKey;
    private final long uid = new com.sun.security.auth.module.UnixSystem().getUid();
    private record FileIdentity(Object key, long size, FileTime modified, int mode) {}

    private ExecutionJavaReports(ExecutionPaths paths, ExecutionPaths.Task task, Path target, Path directory) throws IOException {
        this.paths = paths; this.task = task; this.target = target; this.directory = directory;
        targetKey = paths.privateDirectoryKey(task, target);
        directoryKey = paths.privateDirectoryKey(task, directory);
    }

    static ExecutionJavaReports prepare(ExecutionPaths paths, ExecutionPaths.Task task, OwnedProcess.Budget budget) throws IOException {
        check(budget);
        Path backend = paths.project(task).resolve("backend");
        paths.privateDirectoryKey(task, backend);
        Path target = backend.resolve("target");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("EXECUTION_OUTPUT_NOT_CLEAN");
        // CREATE_NEW directories; no cleanup or adoption of old test evidence.
        ExecutionRootLease.directory(target);
        var result = new ExecutionJavaReports(paths, task, target,
                ExecutionRootLease.directory(target.resolve("surefire-reports")));
        check(budget);
        return result;
    }

    ExecutionJavaReport.Summary read(OwnedProcess.Budget budget) throws IOException {
        try {
            var files = inventory(budget);
            var xml = new ArrayList<byte[]>();
            for (var entry : files.entrySet()) {
                check(budget);
                if (!entry.getKey().endsWith(".xml")) continue;
                if (xml.size() >= ExecutionJavaReport.MAX_FILES) throw invalid();
                Path file = directory.resolve(entry.getKey());
                if (!entry.getValue().equals(identity(file))) throw invalid();
                byte[] bytes;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(ExecutionJavaReport.FILE_MAX_BYTES + 1);
                }
                if (bytes.length != entry.getValue().size() || !entry.getValue().equals(identity(file))) throw invalid();
                xml.add(bytes);
            }
            var summary = ExecutionJavaReport.parse(xml);
            if (!files.equals(inventory(budget))) throw invalid();
            check(budget);
            return summary;
        } catch (IOException | RuntimeException error) {
            if (Set.of("EXECUTION_TIMEOUT", "EXECUTION_CANCELLED").contains(Objects.toString(error.getMessage(), "")))
                throw new IOException(error.getMessage());
            throw invalid();
        }
    }

    private Map<String, FileIdentity> inventory(OwnedProcess.Budget budget) throws IOException {
        check(budget);
        verifyDirectories();
        var result = new TreeMap<String, FileIdentity>();
        long bytes = 0;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path file : entries) {
                check(budget);
                String name = file.getFileName().toString();
                if (result.size() >= 2 * ExecutionJavaReport.MAX_FILES || name.length() > 255
                        || !name.matches("(?:TEST-[A-Za-z0-9_$.-]+\\.xml|[A-Za-z0-9_$][A-Za-z0-9_$.-]*\\.txt)")) throw invalid();
                var identity = identity(file);
                if ((bytes += identity.size()) > ExecutionJavaReport.TOTAL_MAX_BYTES) throw invalid();
                result.put(name, identity);
            }
        }
        verifyDirectories();
        return result;
    }

    private FileIdentity identity(Path file) throws IOException {
        ExecutionRootLease.noLinks(file);
        var attributes = ExecutionRootLease.attributes(file);
        var unix = Files.readAttributes(file, "unix:uid,nlink,mode", LinkOption.NOFOLLOW_LINKS);
        int mode = ((Number) unix.get("mode")).intValue();
        // Surefire recreates report files (normal umask applies); parent directories stay private.
        // https://github.com/apache/maven-surefire/blob/surefire-3.5.6/maven-surefire-common/src/main/java/org/apache/maven/plugin/surefire/report/StatelessXmlReporter.java
        if (!attributes.isRegularFile() || attributes.fileKey() == null || attributes.size() < 0
                || attributes.size() > ExecutionJavaReport.FILE_MAX_BYTES || (mode & 07022) != 0
                || ((Number) unix.get("uid")).longValue() != uid || ((Number) unix.get("nlink")).longValue() != 1)
            throw invalid();
        return new FileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), mode);
    }

    private void verifyDirectories() throws IOException {
        if (!targetKey.equals(paths.privateDirectoryKey(task, target))
                || !directoryKey.equals(paths.privateDirectoryKey(task, directory))) throw invalid();
    }
    private static void check(OwnedProcess.Budget budget) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("EXECUTION_CANCELLED");
        var remaining = budget.remaining();
        if (remaining == null || remaining.isZero() || remaining.isNegative()) throw new IOException("EXECUTION_TIMEOUT");
    }
    private static IOException invalid() { return new IOException("EXECUTION_REPORT_INVALID"); }
}
