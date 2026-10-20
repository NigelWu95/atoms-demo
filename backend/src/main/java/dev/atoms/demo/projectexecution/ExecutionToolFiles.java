package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Pins operator-installed executable/library bytes; not an authenticity check or an OS sandbox. */
final class ExecutionToolFiles {
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_FILE = 512L << 20, MAX_TOTAL = 1L << 30;
    private final Path root;
    private final List<String> included;
    private final Map<Path, Stamp> stamps;
    private final Summary summary;
    private record Stamp(Object key, long size, int mode, long uid, String digest) {}
    record Summary(String sha256, int files, long bytes) {}

    private ExecutionToolFiles(Path root, List<String> included, Map<Path, Stamp> stamps) {
        this.root = root; this.included = included; this.stamps = Map.copyOf(stamps);
        var digest = sha256();
        long bytes = 0; int files = 0;
        for (var entry : stamps.entrySet()) {
            Stamp stamp = entry.getValue();
            String line = root.relativize(entry.getKey()) + "\0" + stamp.mode + "\0" + stamp.size + "\0" + stamp.digest + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
            if (!stamp.digest.equals("directory")) { files++; bytes += stamp.size; }
        }
        summary = new Summary(HexFormat.of().formatHex(digest.digest()), files, bytes);
    }

    static ExecutionToolFiles capture(Path root, List<String> included, OwnedProcess.Budget budget) throws IOException {
        checkBudget(budget);
        try {
            if (root == null || !root.isAbsolute() || !root.normalize().equals(root)
                    || !root.equals(root.toRealPath()) || included == null || included.isEmpty() || included.size() > 8)
                throw unsupported();
            included = List.copyOf(included);
            if (new HashSet<>(included).size() != included.size()) throw unsupported();
            for (String name : included) {
                if (name.equals(".")) {
                    if (included.size() != 1) throw unsupported();
                } else if (name.equals("..") || !name.matches("[a-zA-Z0-9_.-]{1,80}")) throw unsupported();
            }
            ExecutionRootLease.noLinks(root);
            var stamps = new TreeMap<Path, Stamp>();
            long[] bytes = {0};
            long uid = new com.sun.security.auth.module.UnixSystem().getUid();
            class Scanner extends SimpleFileVisitor<Path> {
                private void record(Path path, BasicFileAttributes before) throws IOException {
                    checkBudget(budget);
                    if (stamps.size() >= MAX_ENTRIES || path.getNameCount() - root.getNameCount() > 32
                            || path.toString().length() > 4096 || path.toString().chars().anyMatch(c -> c < 32 || c == 127)
                            || (!before.isDirectory() && !before.isRegularFile()) || before.fileKey() == null)
                        throw unsupported();
                    int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                    long owner = ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
                    if ((mode & 06022) != 0 || (owner != 0 && owner != uid)) throw unsupported();
                    long size = before.isDirectory() ? 0 : before.size();
                    String content = "directory";
                    if (before.isRegularFile()) {
                        if (size < 0 || size > MAX_FILE || bytes[0] > MAX_TOTAL - size
                                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                            throw unsupported();
                        bytes[0] += size;
                        var hash = sha256(); long read = 0;
                        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                            byte[] buffer = new byte[65536]; int count;
                            while ((count = input.read(buffer)) != -1) {
                                checkBudget(budget);
                                if ((read += count) > size) throw unsupported();
                                hash.update(buffer, 0, count);
                            }
                        }
                        var after = ExecutionRootLease.attributes(path);
                        if (read != size || !Objects.equals(before.fileKey(), after.fileKey()) || size != after.size()
                                || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw unsupported();
                        content = HexFormat.of().formatHex(hash.digest());
                    }
                    stamps.put(path, new Stamp(before.fileKey(), size, mode, owner, content));
                }
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    record(path, attributes); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    record(path, attributes); return FileVisitResult.CONTINUE;
                }
            }
            var scanner = new Scanner();
            var rootAttributes = ExecutionRootLease.attributes(root);
            if (!rootAttributes.isDirectory()) throw unsupported();
            scanner.record(root, rootAttributes);
            for (String name : included) {
                Files.walkFileTree(name.equals(".") ? root : root.resolve(name), Set.of(), 33, scanner);
            }
            checkBudget(budget);
            if (!Objects.equals(rootAttributes.fileKey(), ExecutionRootLease.attributes(root).fileKey())) throw unsupported();
            return new ExecutionToolFiles(root, included, stamps);
        } catch (IOException | RuntimeException error) {
            if ("EXECUTION_TIMEOUT".equals(error.getMessage())) throw new IOException("EXECUTION_TIMEOUT");
            throw unsupported();
        }
    }

    void verify(OwnedProcess.Budget budget) throws IOException {
        try {
            var current = capture(root, included, budget);
            if (!stamps.equals(current.stamps)) throw unsupported();
        } catch (IOException error) {
            if ("EXECUTION_TIMEOUT".equals(error.getMessage())) throw error;
            throw new IOException("EXECUTION_TOOL_CHANGED");
        }
    }
    Summary summary() { return summary; }
    private static void checkBudget(OwnedProcess.Budget budget) throws IOException {
        try {
            var left = budget.remaining();
            if (Thread.currentThread().isInterrupted() || left == null || left.isZero() || left.isNegative()) throw new IOException();
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_TIMEOUT"); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IOException unsupported() { return new IOException("EXECUTION_TOOL_UNSUPPORTED"); }
}
