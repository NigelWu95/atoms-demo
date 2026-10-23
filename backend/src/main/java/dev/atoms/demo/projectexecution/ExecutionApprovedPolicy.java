package dev.atoms.demo.projectexecution;

import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded source-external approval; pins identity and bytes, not a sandbox against the same OS user. */
final class ExecutionApprovedPolicy {
    private static final int MAX_BYTES = 65536;
    private record Identity(Object parent, Object file, long bytes, int mode, long owner) {}
    private final Path file;
    private final Identity identity;
    private final byte[] bytes;
    private final ExecutionNpmPolicy npm;
    private final ExecutionMavenPolicy maven;

    private ExecutionApprovedPolicy(Path file, Identity identity, byte[] bytes) throws IOException {
        this.file = file; this.identity = identity; this.bytes = bytes;
        npm = new ExecutionNpmPolicy(bytes); maven = new ExecutionMavenPolicy(bytes);
    }

    static ExecutionApprovedPolicy capture(Path file, List<Path> protectedRoots, OwnedProcess.Budget budget) throws IOException {
        remaining(budget);
        try {
            if (file == null || !file.isAbsolute() || !file.normalize().equals(file)
                    || protectedRoots == null || protectedRoots.stream().anyMatch(root -> file.startsWith(root.toAbsolutePath().normalize())))
                throw invalid();
            Identity before = identity(file);
            byte[] bytes = read(file, budget);
            if (bytes.length != before.bytes() || !before.equals(identity(file))) throw invalid();
            var result = new ExecutionApprovedPolicy(file, before, bytes);
            result.verify(budget);
            return result;
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }

    void verify(OwnedProcess.Budget budget) throws IOException {
        remaining(budget);
        try {
            if (!identity.equals(identity(file)) || !Arrays.equals(bytes, read(file, budget))
                    || !identity.equals(identity(file))) throw invalid();
            remaining(budget);
        } catch (IOException | RuntimeException error) { throw sanitized(error); }
    }
    ExecutionNpmPolicy npm() { return npm; }
    ExecutionMavenPolicy maven() { return maven; }

    private static Identity identity(Path path) throws IOException {
        ExecutionRootLease.noLinks(path);
        var parent = ExecutionRootLease.attributes(path.getParent());
        var file = ExecutionRootLease.attributes(path);
        long uid = new com.sun.security.auth.module.UnixSystem().getUid();
        int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
        long owner = ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
        int parentMode = ((Number) Files.getAttribute(path.getParent(), "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
        long parentOwner = ((Number) Files.getAttribute(path.getParent(), "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
        if (!parent.isDirectory() || parent.fileKey() == null || !file.isRegularFile() || file.fileKey() == null
                || file.size() < 1 || file.size() > MAX_BYTES || (mode & 06022) != 0 || (parentMode & 0022) != 0
                || (owner != 0 && owner != uid) || (parentOwner != 0 && parentOwner != uid)
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw invalid();
        return new Identity(parent.fileKey(), file.fileKey(), file.size(), mode, owner);
    }
    private static byte[] read(Path file, OwnedProcess.Budget budget) throws IOException {
        remaining(budget);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid();
            remaining(budget); return bytes;
        }
    }
    private static void remaining(OwnedProcess.Budget budget) throws IOException {
        var left = budget.remaining();
        if (Thread.currentThread().isInterrupted()) throw new IOException("EXECUTION_CANCELLED");
        if (left == null || left.isZero() || left.isNegative()) throw new IOException("EXECUTION_TIMEOUT");
    }
    private static IOException sanitized(Exception error) {
        String code = Objects.toString(error.getMessage(), "");
        return Set.of("EXECUTION_TIMEOUT", "EXECUTION_CANCELLED").contains(code) ? new IOException(code) : invalid();
    }
    private static IOException invalid() { return new IOException("EXECUTION_DEPENDENCY_POLICY_INVALID"); }
}
