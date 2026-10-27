package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Authorized UI reads of immutable source. No filesystem locations or Git identifiers cross this boundary. */
@Service
public final class ProjectSourceWorkbenchGateway {
    static final int FILE_PREVIEW_BYTES = 256 << 10;
    private final ProjectSourceService service;
    private final SourceQuery query;
    private final ProjectSourceEnvironment environment;
    private final Semaphore readers = new Semaphore(4);

    ProjectSourceWorkbenchGateway(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment) {
        this.service = service; this.environment = environment;
        query = new SourceQuery(service, repository, environment);
    }

    public record Version(UUID id, long versionNo, String treeSha256, int fileCount, long treeBytes,
                          UUID basedOnId, UUID restoredFromId, String templateId, Instant createdAt) {}
    public record Versions(List<Version> items, Long nextBeforeVersion) {}
    public record Entry(String path, int mode, long bytes, String sha256) {}
    public record FileView(String path, int mode, long bytes, String content, boolean truncated) {
        @Override public String toString() { return "FileView[path=" + path + ",bytes=" + bytes + "]"; }
    }
    public record Change(String path, String kind, String beforeSha256, String afterSha256,
                         Integer beforeMode, Integer afterMode, long beforeBytes, long afterBytes) {}
    public record Diff(List<Change> changes, String preview, boolean truncated) {}
    public record Operation(UUID id, String kind, String status, UUID resultVersionId,
                            String errorCode, Instant createdAt, Instant finishedAt) {}

    public Versions versions(UUID owner, UUID project, Long before, int limit) {
        if (limit < 1 || limit > 50 || before != null && before < 1) throw invalid();
        var result = query.versions(owner, project, before, limit + 1);
        var page = result.stream().limit(limit).map(v -> new Version(v.id(), v.versionNo(), v.treeSha256(),
                v.fileCount(), v.treeBytes(), v.basedOnId(), v.restoredFromId(), v.templateId(), v.createdAt())).toList();
        return new Versions(page, result.size() > limit ? page.get(page.size() - 1).versionNo() : null);
    }

    public List<Entry> tree(UUID owner, UUID project, UUID version) {
        return read(deadline -> query.tree(owner, project, version, deadline).stream()
                .map(f -> new Entry(f.path(), f.mode(), f.bytes(), f.sha256())).toList());
    }

    public FileView file(UUID owner, UUID project, UUID version, String path) {
        return read(deadline -> {
            var file = query.file(owner, project, version, path, deadline);
            byte[] bytes = file.content(); int length = Math.min(FILE_PREVIEW_BYTES, bytes.length);
            // Do not cut a UTF-8 code point in half. Source policy has already validated the complete text.
            if (length < bytes.length) while (length > 0 && (bytes[length] & 0xc0) == 0x80) length--;
            return new FileView(file.path(), file.mode(), file.size(), new String(bytes, 0, length, StandardCharsets.UTF_8), length < bytes.length);
        });
    }

    public Diff diff(UUID owner, UUID project, UUID before, UUID after) {
        return read(deadline -> {
            var result = query.diff(owner, project, before, after, deadline);
            return new Diff(result.changes().stream().map(c -> new Change(c.path(), c.kind(), c.beforeSha256(),
                    c.afterSha256(), c.beforeMode(), c.afterMode(), c.beforeBytes(), c.afterBytes())).toList(), result.preview(), result.truncated());
        });
    }

    public Operation operation(UUID owner, UUID project, UUID id) {
        var op = service.operation(owner, project, id);
        return new Operation(op.id(), op.kind(), op.status(), op.resultVersionId(), op.errorCode(), op.createdAt(), op.finishedAt());
    }

    private <T> T read(Function<SourceDeadline, T> work) {
        if (!readers.tryAcquire()) throw new ApiException(429, "SOURCE_READ_CAPACITY", "源码读取繁忙，请稍后重试。");
        try {
            Duration limit = environment.operationTimeout();
            return work.apply(new SourceDeadline(limit.compareTo(Duration.ofSeconds(30)) < 0 ? limit : Duration.ofSeconds(30)));
        } catch (IllegalArgumentException invalid) { throw invalid(); }
        finally { readers.release(); }
    }
    private static ApiException invalid() { return new ApiException(422, "SOURCE_INVALID_INPUT", "源码路径或分页参数无效。"); }
}
