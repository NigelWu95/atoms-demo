package dev.atoms.demo.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Cooperative OS lock: never unlink its file, even after closing the scheduler. */
public final class BuildRootLease implements AutoCloseable {
    private static final java.util.concurrent.ConcurrentMap<Path, BuildRootLease> LOCAL_ROOTS = new java.util.concurrent.ConcurrentHashMap<>();
    private final WorkspacePaths paths;
    private final FileChannel channel;
    private final FileLock lock;
    private final int maxDirectories;
    private final long maxBytes;
    private final long minFreeBytes;
    private final Object lockKey;
    private final Path rootKey;

    public BuildRootLease(WorkspacePaths paths) throws IOException { this(paths, 32, 8L << 30, 2L << 30); }
    BuildRootLease(WorkspacePaths paths, int maxDirectories, long maxBytes, long minFreeBytes) throws IOException {
        this.paths = paths;
        this.maxDirectories = maxDirectories;
        this.maxBytes = maxBytes;
        this.minFreeBytes = minFreeBytes;
        rootKey = paths.root();
        // On POSIX, closing another descriptor for this inode can release this JVM's lock.
        // Reject a second local lease BEFORE opening/closing any descriptor for the lock file.
        if (LOCAL_ROOTS.putIfAbsent(rootKey, this) != null) throw new IOException("Build root already has a scheduler");
        Path file = rootKey.resolve(".scheduler.lock");
        FileChannel opened;
        try {
            try {
                opened = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(WorkspacePaths.FILE_MODE));
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                BuildArtifacts.read(file, 0);
                opened = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (IOException | RuntimeException error) {
            LOCAL_ROOTS.remove(rootKey, this); throw error;
        }
        try {
            // File locks are advisory: every platform scheduler must cooperate with this same stable inode.
            // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html
            lock = opened.tryLock();
            if (lock == null) throw new IOException("Build root already has a scheduler");
            lockKey = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            if (lockKey == null) throw WorkspacePaths.rejected();
            channel = opened;
        } catch (IOException | OverlappingFileLockException error) {
            try { opened.close(); } finally { LOCAL_ROOTS.remove(rootKey, this); }
            throw new IOException("Build root lease is unavailable");
        }
    }

    public void checkCapacity() throws IOException { checkBudget(true); }
    public void checkUsage() throws IOException { checkBudget(false); }
    private void checkBudget(boolean admission) throws IOException {
        Path root = paths.root();
        if (!lock.isValid() || !lockKey.equals(Files.readAttributes(root.resolve(".scheduler.lock"),
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) throw WorkspacePaths.rejected();
        long directories = 0;
        try (var children = Files.list(root)) {
            for (var iterator = children.iterator(); iterator.hasNext();) {
                Path child = iterator.next();
                if (Files.isSymbolicLink(child)) throw WorkspacePaths.rejected();
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (!child.getFileName().toString().matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw WorkspacePaths.rejected();
                    directories++;
                }
            }
        }
        if (directories + (admission ? 1 : 0) > maxDirectories || Files.getFileStore(root).getUsableSpace() < minFreeBytes)
            throw new IOException("Build workspace capacity exceeded");
        // Do not follow npm symlinks or count their targets outside the workspace.
        Files.walkFileTree(root, budgetVisitor(maxBytes));
        paths.root(); // A disappearing configured root is not a transient build child.
    }
    static java.nio.file.SimpleFileVisitor<Path> budgetVisitor(long maxBytes) {
        return new java.nio.file.SimpleFileVisitor<>() {
            private long total;
            @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                total=Math.addExact(total,attributes.size());
                if(total>=maxBytes) throw new IOException("Build workspace capacity exceeded");
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFileFailed(Path file,IOException error) throws IOException {
                if(error instanceof java.nio.file.NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE;
                throw error;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path directory,IOException error) throws IOException {
                if(error!=null && !(error instanceof java.nio.file.NoSuchFileException)) throw error;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        };
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
        LOCAL_ROOTS.remove(rootKey, this);
    }
}
