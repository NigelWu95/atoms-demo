package dev.atoms.demo.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dedicated local storage, not an OS sandbox. Never deletes or adopts an unmarked directory. */
public final class ProjectSourcePaths implements AutoCloseable {
    static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private final Root source;
    private final Root workspace;

    public ProjectSourcePaths(Path source, Path workspace) throws IOException {
        Path sourcePath = validateRoot(source), workPath = validateRoot(workspace);
        if (sourcePath.startsWith(workPath) || workPath.startsWith(sourcePath)) throw unavailable();
        this.source = new Root(sourcePath, "source");
        try { this.workspace = new Root(workPath, "workspace"); }
        catch (IOException | RuntimeException error) { this.source.close(); throw error; }
    }

    public Path root() throws IOException { return source.checked(); }
    public Path workRoot() throws IOException { return workspace.checked(); }
    public void verify() throws IOException { source.checked(); workspace.checked(); }

    public Path createProject(UUID projectId) throws IOException {
        return Files.createDirectory(root().resolve(Objects.requireNonNull(projectId).toString()),
                PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
    }

    public Path createWorkspace(UUID taskId) throws IOException {
        return Files.createDirectory(workRoot().resolve(Objects.requireNonNull(taskId).toString()),
                PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
    }

    @Override public void close() throws IOException {
        try { workspace.close(); } finally { source.close(); }
    }

    static Path validateRoot(Path value) throws IOException {
        if (value == null || !value.isAbsolute()) throw unavailable();
        Path path = value.normalize();
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        Path cwd = Path.of("").toRealPath();
        if (path.getNameCount() < 2 || home.startsWith(path) || cwd.startsWith(path)
                || Files.exists(path.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        noLinks(path);
        return path;
    }

    static void noLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw unavailable();
        }
    }

    static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    static IOException unavailable() { return new IOException("Project source storage is unavailable or unsafe"); }

    private static final class Root implements AutoCloseable {
        private static final Set<Object> OWNED_ROOTS = ConcurrentHashMap.newKeySet();
        private final Path path;
        private final Path marker;
        private final Object rootKey;
        private final Object markerKey;
        private final UserPrincipal owner;
        private final byte[] expected;
        private final FileChannel channel;
        private final FileLock lease;
        private boolean closed;

        Root(Path path, String kind) throws IOException {
            this.path = path;
            this.marker = path.resolve(".atoms-source-root");
            this.owner = Files.getOwner(Path.of(System.getProperty("user.home")));
            this.expected = ("atoms-project-source-v1\n" + kind + "\n").getBytes(StandardCharsets.UTF_8);
            boolean fresh = !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            if (fresh) Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            checkDirectory();
            rootKey = attributes(path).fileKey();
            if (rootKey == null || !OWNED_ROOTS.add(rootKey)) throw unavailable();
            FileChannel opened = null;
            try {
                if (fresh) {
                    opened = FileChannel.open(marker,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(FILE_MODE));
                } else {
                    checkMarker();
                    opened = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                }
                lease = opened.tryLock();
                if (lease == null) throw unavailable();
                if (fresh) {
                    var bytes = ByteBuffer.wrap(expected);
                    while (bytes.hasRemaining()) opened.write(bytes);
                    opened.force(true);
                }
                checkMarker();
                markerKey = attributes(marker).fileKey();
                if (markerKey == null) throw unavailable();
                channel = opened;
                checked();
            } catch (IOException | RuntimeException error) {
                try { if (opened != null) opened.close(); }
                finally { OWNED_ROOTS.remove(rootKey); }
                if (error instanceof OverlappingFileLockException) throw unavailable();
                throw error;
            }
        }

        synchronized Path checked() throws IOException {
            if (closed || !lease.isValid()) throw unavailable();
            noLinks(path);
            checkDirectory();
            checkMarker();
            if (!rootKey.equals(attributes(path).fileKey()) || !markerKey.equals(attributes(marker).fileKey())
                    || channel.size() != expected.length) throw unavailable();
            // Use the SAME channel: closing another descriptor may release POSIX locks for this JVM.
            // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html
            var bytes = ByteBuffer.allocate(expected.length);
            channel.position(0);
            while (bytes.hasRemaining()) { if (channel.read(bytes) < 0) throw unavailable(); }
            if (!Arrays.equals(expected, bytes.array())) throw unavailable();
            return path;
        }

        private void checkDirectory() throws IOException {
            if (!attributes(path).isDirectory() || !owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                    || !DIRECTORY_MODE.equals(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) throw unavailable();
        }

        private void checkMarker() throws IOException {
            if (!attributes(marker).isRegularFile() || attributes(marker).size() > 64
                    || !owner.equals(Files.getOwner(marker, LinkOption.NOFOLLOW_LINKS))
                    || !FILE_MODE.equals(Files.getPosixFilePermissions(marker, LinkOption.NOFOLLOW_LINKS))
                    || ((Number) Files.getAttribute(marker, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                throw unavailable();
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { channel.close(); } finally { OWNED_ROOTS.remove(rootKey); }
        }
    }
}
