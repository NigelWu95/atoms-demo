package dev.atoms.demo.agent;

import dev.atoms.demo.execution.OwnedProcess;
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

/** Version probing is explicit and credential-free; no update or personal configuration commands. */
final class AgentCliProbe {
    // Exact, behavior-tested releases only; never accept an arbitrary semver range.
    static final java.util.Set<String> SUPPORTED_VERSIONS = java.util.Set.of("2.1.302", "2.1.303");
    private AgentCliProbe() {}

    static Identity verify(Path executable, Path config, Path temporary) throws IOException {
        return verify(executable, config, temporary, new AgentDeadline(java.time.Duration.ofSeconds(10))::remaining);
    }

    static Identity verify(Path executable, Path config, Path temporary, OwnedProcess.Budget budget) throws IOException {
        budget.remaining();
        Identity identity = inspect(executable, budget);
        var builder = new ProcessBuilder(identity.path.toString(), "--bare", "--restricted", "--version")
                .directory(temporary.toFile());
        builder.environment().clear();
        builder.environment().putAll(AgentInvocation.probeEnvironment(config, temporary));
        execute(builder, budget);
        identity.verifyUnchanged(budget);
        return identity;
    }

    static void execute(ProcessBuilder builder, OwnedProcess.Budget budget) throws IOException {
        try {
            new OwnedProcess().run(builder, "", budget, () -> false, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), stream -> {
                requireVersion(0, stream.readNBytes(4097)); return Boolean.TRUE;
            });
        } catch (OwnedProcess.Failure error) {
            if (error.stopped() == null) throw new IOException("AGENT_CLI_PROBE_UNSETTLED");
            if ("AGENT_TIMEOUT".equals(error.getMessage())) throw new IOException("AGENT_TIMEOUT");
            throw unsupported();
        }
    }

    static void requireVersion(int exit, byte[] output) throws IOException {
        if (exit != 0 || SUPPORTED_VERSIONS.stream().noneMatch(version ->
                Arrays.equals(output, (version + " (Claude Code)\n").getBytes(StandardCharsets.UTF_8))))
            throw unsupported();
    }

    static Identity inspect(Path executable) throws IOException {
        return inspect(executable, new AgentDeadline(java.time.Duration.ofSeconds(10))::remaining);
    }

    static Identity inspect(Path executable, OwnedProcess.Budget budget) throws IOException {
        budget.remaining();
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
                    budget.remaining();
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
            verifyUnchanged(new AgentDeadline(java.time.Duration.ofSeconds(10))::remaining);
        }
        void verifyUnchanged(OwnedProcess.Budget budget) throws IOException {
            Identity current;
            try { current = inspect(path, budget); }
            catch (IOException error) {
                if ("AGENT_TIMEOUT".equals(error.getMessage())) throw error;
                throw new IOException("AGENT_CLI_CHANGED");
            }
            if (!path.equals(current.path) || !fileKey.equals(current.fileKey) || size != current.size
                    || !MessageDigest.isEqual(digest, current.digest)) throw new IOException("AGENT_CLI_CHANGED");
        }
    }

    private static IOException unsupported() { return new IOException("AGENT_CLI_UNSUPPORTED"); }
}
