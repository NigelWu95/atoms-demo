package dev.atoms.demo.workspace;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Bounded artifact reads, including when a file grows after the metadata check. */
public final class BuildArtifacts {
    private BuildArtifacts() {}

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public static byte[] read(Path path, int limit) throws IOException {
        WorkspacePaths.noLinks(path);
        var attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (limit < 0 || limit >= Integer.MAX_VALUE || !attributes.isRegularFile() || attributes.size() > limit
                || !attributes.owner().equals(Files.getOwner(Path.of(System.getProperty("user.home"))))
                || !Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    .containsAll(attributes.permissions())
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1)
            throw WorkspacePaths.rejected();
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var input = Channels.newInputStream(channel)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw WorkspacePaths.rejected();
            return bytes;
        }
    }
}
