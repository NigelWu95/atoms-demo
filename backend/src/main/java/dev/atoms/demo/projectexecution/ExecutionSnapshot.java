package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Materializes one published source version, never a mutable Agent workspace or latest-template export. */
final class ExecutionSnapshot {
    private static final Set<String> OUTPUTS = Set.of("frontend/node_modules", "frontend/dist",
            "frontend/coverage", "frontend/test-results", "backend/target");
    private final ProjectSourceReadGateway source;
    private final ExecutionPaths paths;

    ExecutionSnapshot(ProjectSourceReadGateway source, ExecutionPaths paths) { this.source = source; this.paths = paths; }

    static final class Materialized {
        private final ExecutionSnapshot issuer;
        private final UUID owner, project;
        private final ProjectSourceReadGateway.Snapshot snapshot;
        private final ExecutionPaths.Task task;
        private final SourceExecutionBudget budget;
        private final Map<String, Object> files, directories;
        private Materialized(ExecutionSnapshot issuer, UUID owner, UUID project,
                             ProjectSourceReadGateway.Snapshot snapshot, ExecutionPaths.Task task,
                             SourceExecutionBudget budget, Map<String, Object> files, Map<String, Object> directories) {
            this.issuer = issuer; this.owner = owner; this.project = project; this.snapshot = snapshot;
            this.task = task; this.budget = budget; this.files = Map.copyOf(files); this.directories = Map.copyOf(directories);
        }
        SourceVersion sourceVersion() { return snapshot.version(); }
        SourceTree tree() { return snapshot.tree(); }
    }

    Materialized materialize(UUID owner, UUID project, UUID version, ExecutionPaths.Task task,
                             SourceExecutionBudget budget) throws IOException {
        outsideTransaction(); remaining(budget);
        var snapshot = read(owner, project, version, budget);
        Path root = paths.project(task);
        paths.checkCapacity(budget::remaining); remaining(budget);
        try (var entries = Files.newDirectoryStream(root)) {
            if (entries.iterator().hasNext()) throw changed();
        }
        var directories = new HashMap<String, Object>();
        var files = new HashMap<String, Object>();
        directories.put("", directoryKey(root));
        for (var file : snapshot.tree().files()) {
            remaining(budget);
            paths.project(task);
            Path target = root.resolve(file.path());
            Path parent = root;
            for (Path component : root.relativize(target.getParent())) {
                if (component.toString().isEmpty()) continue;
                parent = parent.resolve(component);
                String relative = root.relativize(parent).toString();
                if (!directories.containsKey(relative)) {
                    ExecutionRootLease.directory(parent);
                    directories.put(relative, directoryKey(parent));
                } else if (!directories.get(relative).equals(directoryKey(parent))) throw changed();
            }
            byte[] content = file.content();
            try (var output = Files.newByteChannel(target,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(permissions(file.mode())))) {
                for (int offset = 0; offset < content.length;) {
                    remaining(budget);
                    var chunk = ByteBuffer.wrap(content, offset, Math.min(65536, content.length - offset));
                    while (chunk.hasRemaining()) { remaining(budget); offset += output.write(chunk); }
                }
            }
            Files.setPosixFilePermissions(target, permissions(file.mode()));
            files.put(file.path(), fileKey(target, file));
        }
        var result = new Materialized(this, owner, project, snapshot, task, budget, files, directories);
        verify(result);
        return result;
    }

    /** Rechecks ownership, exact source, modes and identities; only fixed generated-output roots are exempt. */
    void verify(Materialized copy) throws IOException {
        outsideTransaction();
        if (copy == null || copy.issuer != this) throw changed();
        remaining(copy.budget);
        Path root = paths.project(copy.task);
        var expected = new HashMap<String, SourceTree.File>();
        copy.tree().files().forEach(file -> expected.put(file.path(), file));
        var seen = new HashSet<String>();
        try {
            Files.walkFileTree(root, Set.of(), 17, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    remaining(copy.budget);
                    String relative = root.relativize(path).toString();
                    if (OUTPUTS.contains(relative)) return FileVisitResult.SKIP_SUBTREE;
                    if (!Objects.equals(copy.directories.get(relative), directoryKey(path))) throw changed();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    remaining(copy.budget);
                    String relative = root.relativize(path).toString();
                    var file = expected.get(relative);
                    if (file == null || !seen.add(relative) || !Objects.equals(copy.files.get(relative), fileKey(path, file)))
                        throw changed();
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Arrays.equals(input.readNBytes(file.size() + 1), file.content())) throw changed();
                    }
                    if (!Objects.equals(copy.files.get(relative), fileKey(path, file))) throw changed();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            if ("EXECUTION_TIMEOUT".equals(error.getMessage())) throw error;
            throw changed();
        }
        if (seen.size() != expected.size()) throw changed();
        paths.project(copy.task); paths.checkCapacity(copy.budget::remaining); remaining(copy.budget);
        // Reading again also checks CURRENT ownership/storage after copying/scanning, not only on admission.
        var current = read(copy.owner, copy.project, copy.sourceVersion().id(), copy.budget);
        if (!current.version().equals(copy.sourceVersion()) || !current.tree().sha256().equals(copy.tree().sha256()))
            throw changed();
        remaining(copy.budget);
    }

    private ProjectSourceReadGateway.Snapshot read(UUID owner, UUID project, UUID version,
                                                   SourceExecutionBudget budget) throws IOException {
        final ProjectSourceReadGateway.Snapshot snapshot;
        try { snapshot = source.read(owner, project, version, budget); }
        catch (ApiException error) {
            if (error.code().equals("SOURCE_OPERATION_TIMEOUT")) throw new IOException("EXECUTION_TIMEOUT");
            throw error;
        }
        try {
            var checked = new ProjectSourcePolicy().validate(snapshot.tree().files());
            var metadata = snapshot.version();
            if (!metadata.id().equals(version) || !metadata.projectId().equals(project)
                    || !metadata.treeSha256().equals(checked.sha256()) || metadata.treeBytes() != checked.totalBytes()
                    || metadata.fileCount() != checked.files().size() || !checked.sha256().equals(snapshot.tree().sha256())
                    || checked.totalBytes() != snapshot.tree().totalBytes()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException error) { throw new IOException("EXECUTION_SOURCE_INVALID"); }
        remaining(budget);
        return snapshot;
    }

    private static Object directoryKey(Path path) throws IOException {
        ExecutionRootLease.noLinks(path);
        var attributes = ExecutionRootLease.attributes(path);
        if (!attributes.isDirectory() || attributes.fileKey() == null
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                .equals(PosixFilePermissions.fromString("rwx------"))) throw changed();
        return attributes.fileKey();
    }
    private static Object fileKey(Path path, SourceTree.File file) throws IOException {
        ExecutionRootLease.noLinks(path);
        var attributes = ExecutionRootLease.attributes(path);
        if (!attributes.isRegularFile() || attributes.fileKey() == null || attributes.size() != file.size()
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(permissions(file.mode()))) throw changed();
        return attributes.fileKey();
    }
    private static Set<java.nio.file.attribute.PosixFilePermission> permissions(int mode) {
        return PosixFilePermissions.fromString(mode == 0100755 ? "rwxr-xr-x" : "rw-r--r--");
    }
    private static void remaining(SourceExecutionBudget budget) throws IOException {
        try {
            var duration = budget.remaining();
            if (Thread.currentThread().isInterrupted() || duration == null || duration.isNegative() || duration.isZero())
                throw new IOException();
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_TIMEOUT"); }
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Execution snapshot I/O cannot hold a database transaction");
    }
    private static IOException changed() { return new IOException("EXECUTION_SOURCE_CHANGED"); }
}
