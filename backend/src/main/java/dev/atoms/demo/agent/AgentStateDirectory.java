package dev.atoms.demo.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Private state with ownership evidence, not an OS sandbox. Never removes residual task data. */
final class AgentStateDirectory implements AutoCloseable {
    private static final byte[] ROOT_MARKER = "atoms-agent-state-v1\n".getBytes(StandardCharsets.UTF_8);
    private static final Set<Object> OPEN_ROOTS = ConcurrentHashMap.newKeySet();
    private final Path root, marker;
    private final Object rootKey, markerKey;
    private final UserPrincipal owner;
    private final FileChannel channel;
    private final FileLock lock;
    private final long maximum, reserve;
    private boolean closed;

    AgentStateDirectory(Path root, List<Path> protectedRoots) throws IOException {
        this(root, protectedRoots, 1L << 30, 512L << 20);
    }

    AgentStateDirectory(Path root, List<Path> protectedRoots, long maximum, long reserve) throws IOException {
        if (maximum < 1 || reserve < 0) throw new IllegalArgumentException("Invalid Agent storage budget");
        this.maximum = maximum; this.reserve = reserve;
        validateRoot(root, protectedRoots);
        this.root = root; this.marker = root.resolve(".atoms-agent-root");
        this.owner = Files.getOwner(Path.of(System.getProperty("user.home")));
        boolean fresh = !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        if (fresh) directory(root);
        rootKey = directoryKey(root);
        if (!OPEN_ROOTS.add(rootKey)) throw unavailable();
        FileChannel opened = null;
        try {
            if (!fresh) checkFile(marker, ROOT_MARKER.length);
            var options = new HashSet<OpenOption>(List.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            if (fresh) options.add(StandardOpenOption.CREATE_NEW);
            opened = FileChannel.open(marker, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            lock = opened.tryLock();
            if (lock == null) throw unavailable();
            if (fresh) {
                var bytes = ByteBuffer.wrap(ROOT_MARKER);
                while (bytes.hasRemaining()) opened.write(bytes);
                opened.force(true);
            }
            markerKey = checkFile(marker, ROOT_MARKER.length).fileKey();
            channel = opened;
            verifyRoot();
        } catch (IOException | RuntimeException error) {
            try { if (opened != null) opened.close(); }
            finally { OPEN_ROOTS.remove(rootKey); }
            throw unavailable();
        }
    }

    synchronized Task create(UUID id) throws IOException {
        Objects.requireNonNull(id);
        checkCapacity();
        Path task = directory(root.resolve(id.toString()));
        Path config = directory(task.resolve("config")), temporary = directory(task.resolve("tmp"));
        String evidence = "atoms-agent-task-v1\n" + id + "\n" + UUID.randomUUID() + "\n";
        Path taskMarker = task.resolve(".atoms-agent-task");
        try (var output = Files.newByteChannel(taskMarker, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var bytes = ByteBuffer.wrap(evidence.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) output.write(bytes);
        }
        var handle = new Task(this, config, temporary, directoryKey(task), directoryKey(config), directoryKey(temporary),
                checkFile(taskMarker, evidence.length()).fileKey(), evidence);
        verify(handle);
        return handle;
    }

    synchronized void verify(Task task) throws IOException {
        verifyRoot();
        if (task == null || task.issuer != this || !task.taskKey.equals(directoryKey(task.config.getParent()))
                || !task.configKey.equals(directoryKey(task.config)) || !task.tmpKey.equals(directoryKey(task.temporary))) throw unavailable();
        Path taskMarker = task.config.getParent().resolve(".atoms-agent-task");
        if (!task.markerKey.equals(checkFile(taskMarker, task.evidence.length()).fileKey())) throw unavailable();
        try (var input = Files.newInputStream(taskMarker, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(task.evidence.length() + 1);
            if (!Arrays.equals(bytes, task.evidence.getBytes(StandardCharsets.UTF_8))) throw unavailable();
        }
    }

    synchronized void checkCapacity() throws IOException {
        verifyRoot();
        if (Files.getFileStore(root).getUsableSpace() < reserve) throw new IOException("AGENT_STATE_CAPACITY");
        long[] used = {0, 0};
        long start = System.nanoTime();
        Files.walkFileTree(root, Set.of(), 64, new SimpleFileVisitor<>() {
            private void check() throws IOException {
                if (++used[1] > 100_000 || System.nanoTime() - start > 10_000_000_000L || Thread.currentThread().isInterrupted())
                    throw new IOException("AGENT_STATE_CAPACITY");
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                check(); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                check();
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw unavailable();
                if (attrs.size() > maximum - used[0]) throw new IOException("AGENT_STATE_CAPACITY");
                used[0] += attrs.size(); return FileVisitResult.CONTINUE;
            }
        });
        verifyRoot();
    }

    private void verifyRoot() throws IOException {
        if (closed || !lock.isValid() || !rootKey.equals(directoryKey(root))
                || !markerKey.equals(checkFile(marker, ROOT_MARKER.length).fileKey())) throw unavailable();
        // Keep one channel: closing another descriptor can release POSIX locks for this JVM.
        // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html
        if (channel.size() != ROOT_MARKER.length) throw unavailable();
        var bytes = ByteBuffer.allocate(ROOT_MARKER.length);
        channel.position(0);
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw unavailable();
        if (!Arrays.equals(bytes.array(), ROOT_MARKER)) throw unavailable();
    }

    private Object directoryKey(Path path) throws IOException {
        noLinks(path);
        var attributes = attributes(path);
        if (!attributes.isDirectory() || attributes.fileKey() == null
                || !owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------")))
            throw unavailable();
        return attributes.fileKey();
    }

    private BasicFileAttributes checkFile(Path path, long length) throws IOException {
        var attributes = attributes(path);
        if (!attributes.isRegularFile() || attributes.fileKey() == null || attributes.size() != length
                || !owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw unavailable();
        return attributes;
    }

    private static void validateRoot(Path path, List<Path> protectedRoots) throws IOException {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path) || path.getNameCount() < 2) throw unavailable();
        noLinks(path);
        if (Path.of(System.getProperty("user.home")).toRealPath().startsWith(path)) throw unavailable();
        var excluded = new ArrayList<>(protectedRoots);
        Path working = Path.of("").toRealPath();
        excluded.add(working);
        for (Path ancestor = working; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) { excluded.add(ancestor); break; }
        }
        for (Path protectedRoot : excluded) {
            if (!protectedRoot.isAbsolute()) throw unavailable();
            noLinks(protectedRoot);
            Path normalized = protectedRoot.normalize();
            if (path.startsWith(normalized) || normalized.startsWith(path)) throw unavailable();
        }
    }

    private static void noLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw unavailable();
    }
    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    private static Path directory(Path path) throws IOException {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    private static IOException unavailable() { return new IOException("AGENT_STATE_UNAVAILABLE"); }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { channel.close(); } finally { OPEN_ROOTS.remove(rootKey); }
    }

    static final class Task {
        private final AgentStateDirectory issuer;
        private final Path config, temporary;
        private final Object taskKey, configKey, tmpKey, markerKey;
        private final String evidence;
        private Task(AgentStateDirectory issuer, Path config, Path temporary, Object taskKey, Object configKey,
                     Object tmpKey, Object markerKey, String evidence) {
            this.issuer = issuer; this.config = config; this.temporary = temporary; this.taskKey = taskKey;
            this.configKey = configKey; this.tmpKey = tmpKey; this.markerKey = markerKey; this.evidence = evidence;
        }
        Path config() { return config; }
        Path temporary() { return temporary; }
    }
}
