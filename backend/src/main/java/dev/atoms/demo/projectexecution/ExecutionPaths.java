package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Private local storage capabilities; logical accounting, not an OS quota or sandbox. */
final class ExecutionPaths implements AutoCloseable {
    private static final String TASK_MARKER = ".atoms-execution-task";
    private final ExecutionRootLease lease;
    private final Path root;
    private final Map<Area, Object> areas = new EnumMap<>(Area.class);
    private final long maxBytes, reserveBytes;

    private enum Area {
        BUILD("builds"), ARTIFACT("artifacts"), RUN("runs");
        final String directory;
        Area(String directory) { this.directory = directory; }
    }

    static final class Task {
        private final ExecutionPaths issuer;
        private final Area area;
        private final Path path;
        private final Object key, projectKey, markerKey;
        private final byte[] marker;

        private Task(ExecutionPaths issuer, Area area, Path path, Object key, Object projectKey,
                     Object markerKey, byte[] marker) {
            this.issuer = issuer; this.area = area; this.path = path; this.key = key;
            this.projectKey = projectKey; this.markerKey = markerKey; this.marker = marker;
        }
    }

    ExecutionPaths(Path root, List<Path> protectedRoots) throws IOException {
        this(root, protectedRoots, ExecutionSettings.ROOT_MAX_BYTES, ExecutionSettings.MIN_FREE_BYTES);
    }

    ExecutionPaths(Path root, List<Path> protectedRoots, long maxBytes, long reserveBytes) throws IOException {
        if (maxBytes <= 0 || reserveBytes < 0) throw ExecutionRootLease.unavailable();
        this.root = root; this.maxBytes = maxBytes; this.reserveBytes = reserveBytes;
        lease = new ExecutionRootLease(root, protectedRoots);
        try {
            for (Area area : Area.values()) {
                Path path = root.resolve(area.directory);
                if (lease.created()) ExecutionRootLease.directory(path);
                areas.put(area, lease.directoryKey(path));
            }
            checkedAreas();
        } catch (IOException | RuntimeException error) {
            lease.close();
            throw ExecutionRootLease.unavailable();
        }
    }

    synchronized Task createBuild(UUID id) throws IOException { return create(Area.BUILD, id); }
    synchronized Task createBuild(UUID id, dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        return create(Area.BUILD, id, budget);
    }
    synchronized Task createArtifact(UUID id) throws IOException { return create(Area.ARTIFACT, id); }
    synchronized Task createArtifact(UUID id, dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        return create(Area.ARTIFACT, id, budget);
    }
    synchronized Task createRun(UUID id) throws IOException { return create(Area.RUN, id); }
    synchronized Task createRun(UUID id, dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        return create(Area.RUN, id, budget);
    }

    /** Read-only reacquisition of sealed artifact storage; never opens build/run tasks or restores process ownership. */
    synchronized Task openArtifact(UUID id, dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        Objects.requireNonNull(id); checkCapacity(budget);
        Path path = root.resolve(Area.ARTIFACT.directory).resolve(id.toString()); Object key = lease.directoryKey(path);
        String prefix = "atoms-execution-task-v1\nARTIFACT\n" + id + "\n";
        int length = prefix.length() + 37;
        Path markerPath = path.resolve(TASK_MARKER); Object markerKey = lease.fileKey(markerPath, length);
        byte[] marker;
        try (var input = Files.newInputStream(markerPath, LinkOption.NOFOLLOW_LINKS)) { marker = input.readNBytes(length + 1); }
        String value = new String(marker, StandardCharsets.UTF_8);
        if (marker.length != length || !value.startsWith(prefix) || !value.endsWith("\n")) throw ExecutionRootLease.unavailable();
        String nonce = value.substring(prefix.length(), value.length() - 1);
        try { if (!UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw ExecutionRootLease.unavailable(); }
        var task = new Task(this, Area.ARTIFACT, path, key, null, markerKey, marker);
        directory(task); checkBudget(budget); return task;
    }

    private Task create(Area area, UUID id) throws IOException {
        return create(area, id, () -> java.time.Duration.ofSeconds(10));
    }

    private Task create(Area area, UUID id, dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        Objects.requireNonNull(id);
        checkCapacity(budget);
        Path path = ExecutionRootLease.directory(root.resolve(area.directory).resolve(id.toString()));
        Object key = lease.directoryKey(path);
        Object projectKey = area == Area.BUILD
                ? lease.directoryKey(ExecutionRootLease.directory(path.resolve("project"))) : null;
        byte[] marker = ("atoms-execution-task-v1\n" + area.name() + "\n" + id + "\n" + UUID.randomUUID() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Path markerPath = path.resolve(TASK_MARKER);
        // CREATE_NEW: partial allocations are evidence, never silently overwritten or removed.
        try (var channel = java.nio.channels.FileChannel.open(markerPath,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var bytes = java.nio.ByteBuffer.wrap(marker);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        var task = new Task(this, area, path, key, projectKey, lease.fileKey(markerPath, marker.length), marker);
        directory(task);
        checkBudget(budget);
        return task;
    }

    synchronized Path directory(Task task) throws IOException {
        checkedAreas();
        if (task == null || task.issuer != this || !task.key.equals(lease.directoryKey(task.path)))
            throw ExecutionRootLease.unavailable();
        Path marker = task.path.resolve(TASK_MARKER);
        if (!task.markerKey.equals(lease.fileKey(marker, task.marker.length))) throw ExecutionRootLease.unavailable();
        try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(input.readNBytes(task.marker.length + 1), task.marker))
                throw ExecutionRootLease.unavailable();
        }
        if (!task.markerKey.equals(lease.fileKey(marker, task.marker.length))) throw ExecutionRootLease.unavailable();
        return task.path;
    }

    synchronized Path project(Task task) throws IOException {
        Path path = directory(task).resolve("project");
        if (task.area != Area.BUILD || !task.projectKey.equals(lease.directoryKey(path)))
            throw ExecutionRootLease.unavailable();
        return path;
    }

    /** Revalidate the held root lease; the returned path is an identity, not a transferable capability. */
    synchronized Path checkedRoot() throws IOException {
        checkedAreas();
        return root;
    }

    synchronized Object privateDirectoryKey(Task task, Path child) throws IOException {
        return lease.directoryKey(ownedChild(task, child));
    }

    synchronized Object privateFileKey(Task task, Path child, long size) throws IOException {
        return lease.fileKey(ownedChild(task, child), size);
    }

    private Path ownedChild(Task task, Path child) throws IOException {
        Path parent = directory(task);
        if (child == null || !child.isAbsolute() || !child.normalize().equals(child)
                || !child.startsWith(parent) || child.equals(parent)) throw ExecutionRootLease.unavailable();
        return child;
    }

    private void checkedAreas() throws IOException {
        lease.checked();
        for (Area area : Area.values())
            if (!areas.get(area).equals(lease.directoryKey(root.resolve(area.directory)))) throw ExecutionRootLease.unavailable();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (!Set.of(".atoms-execution-root", "builds", "artifacts", "runs").contains(name))
                    throw ExecutionRootLease.unavailable();
            }
        }
    }

    synchronized void checkCapacity() throws IOException {
        checkCapacity(() -> java.time.Duration.ofSeconds(10));
    }

    synchronized void checkCapacity(dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        checkBudget(budget);
        checkedAreas();
        long started = System.nanoTime();
        long[] used = {0};
        int[] entries = {0};
        Files.walkFileTree(root, Set.of(), 64, new SimpleFileVisitor<>() {
            private void step() throws IOException {
                checkBudget(budget);
                if (Thread.currentThread().isInterrupted() || ++entries[0] > 100_000
                        || System.nanoTime() - started > 10_000_000_000L) throw ExecutionRootLease.unavailable();
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                step();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                step();
                // npm package symlinks count as leaves. Never follow them outside this root.
                if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) throw ExecutionRootLease.unavailable();
                if (attributes.size() < 0 || attributes.size() > maxBytes - used[0]) throw capacity();
                used[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                step();
                // A running tool may remove its own temporary file during the scan.
                if (!path.equals(root) && error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw ExecutionRootLease.unavailable();
            }
        });
        checkedAreas();
        requireCapacity(used[0], Files.getFileStore(root).getUsableSpace(), maxBytes, reserveBytes);
        checkBudget(budget);
    }

    private static void checkBudget(dev.atoms.demo.execution.OwnedProcess.Budget budget) throws IOException {
        try {
            var remaining = budget.remaining();
            if (remaining == null || remaining.isNegative() || remaining.isZero()) throw new IOException();
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_TIMEOUT"); }
    }

    static void requireCapacity(long used, long available, long max, long reserve) throws IOException {
        if (used < 0 || available < 0 || max <= 0 || reserve < 0 || used > max || available < reserve) throw capacity();
    }
    private static IOException capacity() { return new IOException("EXECUTION_STORAGE_CAPACITY"); }

    @Override public synchronized void close() throws IOException { lease.close(); }
}
