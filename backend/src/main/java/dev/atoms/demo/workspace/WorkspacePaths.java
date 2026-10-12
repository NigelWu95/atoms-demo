package dev.atoms.demo.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.UUID;

/** Private, operator-selected root. Never deletes, traverses symlinks or reuses a task directory. */
public final class WorkspacePaths {
    static final java.util.Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    static final java.util.Set<java.nio.file.attribute.PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private final Path root;
    private final Object rootKey;
    private final UserPrincipal owner;

    public WorkspacePaths(Path configuredRoot) throws IOException {
        root = configuredRoot.toAbsolutePath().normalize();
        if (!configuredRoot.isAbsolute() || root.getNameCount() < 2
                || root.equals(Path.of(System.getProperty("user.home")).toRealPath())
                || root.equals(Path.of("").toRealPath()) || Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
            throw rejected();
        noLinks(root);
        owner = Files.getOwner(Path.of(System.getProperty("user.home")));
        privateDirectory(root);
        rootKey = attributes(root).fileKey();
        if (rootKey == null) throw rejected();
    }

    public Path root() throws IOException {
        privateDirectory(root);
        noLinks(root);
        if (!Objects.equals(rootKey, attributes(root).fileKey())) throw rejected();
        return root;
    }

    public Path task(UUID id) throws IOException { return root().resolve(Objects.requireNonNull(id).toString()); }

    public Path create(UUID id) throws IOException {
        return Files.createDirectory(task(id), PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
    }

    Path checked(UUID id, String relative) throws IOException {
        Path base = task(id), target = base.resolve(relative).normalize();
        if (relative.isEmpty() || Path.of(relative).isAbsolute() || !target.startsWith(base) || target.equals(base)) throw rejected();
        privateDirectory(base);
        for (Path part = target.getParent(); part != null && part.startsWith(base); part = part.getParent()) privateDirectory(part);
        if (Files.isSymbolicLink(target)) throw rejected();
        return target;
    }

    void privateDirectory(Path path) throws IOException {
        if (!attributes(path).isDirectory() || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(DIRECTORY_MODE)) throw rejected();
    }

    static void noLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw rejected();
    }
    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    static IOException rejected() { return new IOException("Workspace path is unavailable or unsafe"); }
}
