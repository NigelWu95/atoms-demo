package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.EnumSet;

/** Admission check, not a filesystem quota. Unknown evidence is retained and never deleted to make room. */
final class SourceStorageBudget {
    private final ProjectSourcePaths paths;
    private final long maximum, reserve;
    SourceStorageBudget(ProjectSourcePaths paths, long maximum, long reserve) {
        if (maximum < 1 || reserve < 0) throw new IllegalArgumentException("Invalid source storage budget");
        this.paths = paths; this.maximum = maximum; this.reserve = reserve;
    }

    void check(long anticipated) throws IOException {
        check(anticipated, new SourceDeadline(Duration.ofSeconds(60)));
    }

    void check(long anticipated, SourceDeadline deadline) throws IOException {
        if (anticipated < 0) throw new IllegalArgumentException("Invalid source reservation");
        deadline.remaining(Duration.ofSeconds(60));
        paths.verify();
        Path source = paths.root(), work = paths.workRoot();
        long available = Math.min(Files.getFileStore(source).getUsableSpace(), Files.getFileStore(work).getUsableSpace());
        if (anticipated > maximum || available < reserve || available - reserve < anticipated) throw capacity();
        long[] bytes = {0}, entries = {0};
        Files.walkFileTree(source, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            private void bounded() throws IOException {
                deadline.remaining(Duration.ofSeconds(60));
                if (++entries[0] > 1_000_000) throw capacity();
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                bounded();
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw ProjectSourcePaths.unavailable();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                bounded();
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw ProjectSourcePaths.unavailable();
                if (attributes.size() > maximum - anticipated - bytes[0]) throw capacity();
                bytes[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        paths.verify();
    }

    private static ApiException capacity() { return new ApiException(409, "SOURCE_STORAGE_CAPACITY", "源码存储预算或磁盘可用空间不足，请联系维护者。"); }
}
