package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;

/** Restoring code appends a version; it neither rewinds history nor executes database migrations. */
final class SourceRestoration {
    private final ProjectSourceService service;
    private final SourceRepository repository;
    private final SourceQuery query;
    SourceRestoration(ProjectSourceService service, SourceRepository repository, SourceQuery query) {
        this.service = service; this.repository = repository; this.query = query;
    }

    Prepared prepare(UUID owner, UUID project, UUID target, UUID base, long revision, String key) {
        service.authorize(owner, project);
        var version = repository.version(project, target);
        if (version == null) throw new ApiException(404, "SOURCE_VERSION_MISSING", "源码版本不存在。");
        var operation = service.admit(owner, project, new SourceRequest("RESTORE", base, revision, target,
                null, null, null, version.treeSha256()), key);
        if (!operation.status().equals("PREPARING") || operation.executionId() != null) return new Prepared(operation, null);
        try { return new Prepared(operation, query.snapshot(owner, project, target).tree()); }
        catch (ApiException error) {
            String code = error.code().startsWith("SOURCE_") ? error.code() : "SOURCE_ACCESS_CHANGED";
            return new Prepared(service.fail(project, operation.id(), code, code.equals("SOURCE_STORAGE_UNAVAILABLE")), null);
        }
    }

    /** A null tree means the persisted operation is returned without scheduling another execution. */
    record Prepared(SourceOperation operation, SourceTree tree) {}
}
