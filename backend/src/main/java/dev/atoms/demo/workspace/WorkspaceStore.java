package dev.atoms.demo.workspace;

import dev.atoms.demo.engineering.EngineeringBundle;
import dev.atoms.demo.engineering.EngineeringZip;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/** Source-only storage. No unpacking of uploads, process execution or automatic cleanup. */
public final class WorkspaceStore {
    private final WorkspacePaths paths;
    public WorkspaceStore(WorkspacePaths paths) { this.paths = paths; }
    public record Prepared(Path work, Path archive) {}

    public Prepared prepare(UUID id, EngineeringBundle bundle) throws IOException {
        // Validate all names/collisions/quotas before creating any task directory.
        byte[] zip = EngineeringZip.write(bundle);
        Path task = paths.create(id);
        for (String directory : java.util.List.of("snapshot", "work", "result", "private"))
            Files.createDirectory(task.resolve(directory), PosixFilePermissions.asFileAttribute(WorkspacePaths.DIRECTORY_MODE));
        Path work = paths.checked(id, "work");
        for (var file : bundle.files()) {
            Path target = work.resolve(file.path());
            Path parent = work;
            for (Path part : work.relativize(target.getParent())) {
                if (part.toString().isEmpty()) continue;
                parent = parent.resolve(part);
                if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS))
                    Files.createDirectory(parent, PosixFilePermissions.asFileAttribute(WorkspacePaths.DIRECTORY_MODE));
                paths.privateDirectory(parent);
            }
            writeNew(paths.checked(id, "work/" + file.path()), file.content());
            if (file.path().equals("backend/mvnw")) Files.setPosixFilePermissions(target,
                    PosixFilePermissions.fromString("rwx------"));
        }
        // CREATE_NEW is exclusive; the DB exposes the archive only after this method completes.
        Path archive = paths.checked(id, "snapshot/engineering.zip");
        writeNew(archive, zip);
        return new Prepared(work, archive);
    }

    static void writeNew(Path path, byte[] bytes) throws IOException {
        try (var output = Files.newByteChannel(path,
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(WorkspacePaths.FILE_MODE))) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
        }
    }
}
