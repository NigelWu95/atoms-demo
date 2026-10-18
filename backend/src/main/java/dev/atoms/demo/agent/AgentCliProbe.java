package dev.atoms.demo.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Version probing is explicit and credential-free; no update or personal configuration commands. */
final class AgentCliProbe {
    private AgentCliProbe() {}

    static Identity verify(Path executable, Path config, Path temporary) throws IOException {
        Identity identity = inspect(executable);
        var builder = new ProcessBuilder(identity.path.toString(), "--bare", "--restricted", "--version")
                .directory(temporary.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().putAll(AgentInvocation.probeEnvironment(config, temporary));
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            // Expected output is one short version line. A filled pipe times out, never grows memory.
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw unsupported();
            requireVersion(process.exitValue(), process.getInputStream().readNBytes(4097));
            identity.verifyUnchanged();
            return identity;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException("AGENT_CLI_PROBE_INTERRUPTED");
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (process.isAlive()) process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("AGENT_CLI_PROBE_UNSETTLED");
                process.getInputStream().close();
            } catch (InterruptedException error) {
                interrupted = true; throw new IOException("AGENT_CLI_PROBE_UNSETTLED");
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    static void requireVersion(int exit, byte[] output) throws IOException {
        if (exit != 0 || !Arrays.equals(output, "2.1.302 (Claude Code)\n".getBytes(StandardCharsets.UTF_8)))
            throw unsupported();
    }

    static Identity inspect(Path executable) throws IOException {
        if (!executable.isAbsolute()) throw unsupported();
        Path real = executable.toRealPath();
        var attributes = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var permissions = Files.getPosixFilePermissions(real, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || !Files.isExecutable(real) || attributes.fileKey() == null
                || attributes.size() == 0 || attributes.size() > (512L << 20)
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) throw unsupported();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536]; int count; long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    if ((total += count) > attributes.size()) throw unsupported();
                    digest.update(buffer, 0, count);
                }
                if (total != attributes.size()) throw unsupported();
            }
            var after = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(attributes.fileKey(), after.fileKey()) || attributes.size() != after.size()
                    || !attributes.lastModifiedTime().equals(after.lastModifiedTime())) throw unsupported();
            return new Identity(real, attributes.fileKey(), attributes.size(), digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    static final class Identity {
        private final Path path;
        private final Object fileKey;
        private final long size;
        private final byte[] digest;
        private Identity(Path path, Object fileKey, long size, byte[] digest) {
            this.path = path; this.fileKey = fileKey; this.size = size; this.digest = digest.clone();
        }
        Path path() { return path; }
        void verifyUnchanged() throws IOException {
            Identity current;
            try { current = inspect(path); }
            catch (IOException error) { throw new IOException("AGENT_CLI_CHANGED"); }
            if (!path.equals(current.path) || !fileKey.equals(current.fileKey) || size != current.size
                    || !MessageDigest.isEqual(digest, current.digest)) throw new IOException("AGENT_CLI_CHANGED");
        }
    }

    private static IOException unsupported() { return new IOException("AGENT_CLI_UNSUPPORTED"); }
}
