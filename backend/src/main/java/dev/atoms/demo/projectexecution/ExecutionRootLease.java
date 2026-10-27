package dev.atoms.demo.projectexecution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Cooperative local ownership only, not a sandbox. Never deletes or adopts an unmarked root. */
final class ExecutionRootLease implements AutoCloseable {
    private static final byte[] MARKER = "atoms-project-execution-v1\n".getBytes(StandardCharsets.UTF_8);
    private static final Set<Object> OWNED = ConcurrentHashMap.newKeySet();
    private final Path root, marker;
    private final long uid;
    private final Object rootKey, markerKey;
    private final FileChannel channel;
    private final FileLock lock;
    private final boolean created;
    private boolean closed;

    ExecutionRootLease(Path root, List<Path> protectedRoots) throws IOException {
        // Native identity, not user.name; macOS/Linux only.
        // https://docs.oracle.com/en/java/javase/17/docs/api/jdk.security.auth/com/sun/security/auth/module/UnixSystem.html
        this(root, protectedRoots, new com.sun.security.auth.module.UnixSystem().getUid());
    }

    static ExecutionRootLease existing(Path root, List<Path> protectedRoots) throws IOException {
        return new ExecutionRootLease(root, protectedRoots, new com.sun.security.auth.module.UnixSystem().getUid(), true);
    }

    ExecutionRootLease(Path root, List<Path> protectedRoots, long uid) throws IOException {
        this(root, protectedRoots, uid, false);
    }

    private ExecutionRootLease(Path root, List<Path> protectedRoots, long uid, boolean requireExisting) throws IOException {
        if (uid <= 0) throw new IOException("EXECUTION_ROOT_REFUSED");
        validateRoot(root, protectedRoots);
        this.root = root; this.marker = root.resolve(".atoms-execution-root"); this.uid = uid;
        created = !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        if (created && requireExisting) throw unavailable();
        if (created) directory(root);
        rootKey = directoryKey(root);
        // Reject local duplicates BEFORE opening another descriptor for the locked inode.
        if (!OWNED.add(rootKey)) throw unavailable();
        FileChannel opened = null;
        try {
            if (!created) fileKey(marker, MARKER.length);
            var options = new HashSet<OpenOption>(List.of(StandardOpenOption.READ, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS));
            if (created) options.add(StandardOpenOption.CREATE_NEW);
            opened = FileChannel.open(marker, options,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            lock = opened.tryLock();
            if (lock == null) throw unavailable();
            if (created) {
                var bytes = ByteBuffer.wrap(MARKER);
                while (bytes.hasRemaining()) opened.write(bytes);
                opened.force(true);
            }
            markerKey = fileKey(marker, MARKER.length);
            channel = opened;
            checked();
        } catch (IOException | RuntimeException error) {
            try { if (opened != null) opened.close(); }
            finally { OWNED.remove(rootKey); }
            throw unavailable();
        }
    }

    boolean created() { return created; }

    synchronized Path checked() throws IOException {
        if (closed || !lock.isValid() || !rootKey.equals(directoryKey(root))
                || !markerKey.equals(fileKey(marker, MARKER.length)) || channel.size() != MARKER.length) throw unavailable();
        // Closing ANY extra descriptor for this inode can release the JVM's POSIX lock.
        // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html
        var bytes = ByteBuffer.allocate(MARKER.length);
        channel.position(0);
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw unavailable();
        if (!Arrays.equals(bytes.array(), MARKER)) throw unavailable();
        return root;
    }

    Object directoryKey(Path path) throws IOException {
        noLinks(path);
        var attributes = attributes(path);
        if (!attributes.isDirectory() || attributes.fileKey() == null || !ownedMode(path, "rwx------")) throw unavailable();
        return attributes.fileKey();
    }

    Object fileKey(Path path, long size) throws IOException {
        noLinks(path);
        var attributes = attributes(path);
        if (!attributes.isRegularFile() || attributes.fileKey() == null || attributes.size() != size
                || !ownedMode(path, "rw-------")
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
            throw unavailable();
        return attributes.fileKey();
    }

    private boolean ownedMode(Path path, String mode) throws IOException {
        return ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() == uid
                && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString(mode));
    }

    private static void validateRoot(Path path, List<Path> protectedRoots) throws IOException {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path) || path.getNameCount() < 2) throw unavailable();
        noLinks(path);
        if (Path.of(System.getProperty("user.home")).toRealPath().startsWith(path)) throw unavailable();
        var excluded = new ArrayList<>(protectedRoots);
        Path cwd = Path.of("").toRealPath();
        excluded.add(cwd);
        for (Path ancestor = cwd; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) { excluded.add(ancestor); break; }
        }
        for (Path reserved : excluded) {
            if (reserved == null || !reserved.isAbsolute() || !reserved.normalize().equals(reserved)) throw unavailable();
            noLinks(reserved);
            if (path.startsWith(reserved) || reserved.startsWith(path)) throw unavailable();
        }
    }

    static void noLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw unavailable();
    }
    static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    static Path directory(Path path) throws IOException {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    static IOException unavailable() { return new IOException("EXECUTION_STORAGE_UNAVAILABLE"); }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { channel.close(); } finally { OWNED.remove(rootKey); }
    }
}
