package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;

/** Bridges an issued sealed candidate to durable admission; publication remains the worker's responsibility. */
final class SourceAcceptance {
    private final ProjectSourceService service;
    private final SourceCandidateStore candidates;
    SourceAcceptance(ProjectSourceService service, SourceCandidateStore candidates) {
        this.service = service; this.candidates = candidates;
    }

    Prepared prepare(UUID owner, UUID project, SourceCandidate candidate, String key) {
        // Multiple adapter instances can share the issuing store. Consume the capability only once.
        synchronized (candidates) { return prepareOnce(owner, project, candidate, key); }
    }

    private Prepared prepareOnce(UUID owner, UUID project, SourceCandidate candidate, String key) {
        if (candidate == null || !owner.equals(candidate.ownerId()) || !project.equals(candidate.projectId()))
            throw ProjectSourceService.conflict("SOURCE_CANDIDATE_UNAVAILABLE");
        var request = new SourceRequest("ACCEPT", candidate.versionId(), candidate.revision(), null, null, null, null, candidate.treeSha256());
        var previous = service.replay(owner, project, request, key);
        if (previous != null) return new Prepared(previous, null);
        candidates.authorize(owner, project, candidate);
        var operation = service.admit(owner, project, request, key);
        // Return persisted retries before reading an obsolete baseline or touching staged files.
        if (!operation.status().equals("PREPARING") || operation.executionId() != null) return new Prepared(operation, null);
        try { return new Prepared(operation, candidates.read(owner, project, candidate)); }
        catch (ApiException error) {
            String code = error.code().startsWith("SOURCE_") ? error.code() : "SOURCE_ACCESS_CHANGED";
            return new Prepared(service.fail(project, operation.id(), code, false), null);
        } finally { candidates.release(candidate); }
    }

    /** tree is present only for an operation that can be queued for execution. */
    record Prepared(SourceOperation operation, SourceTree tree) {}
}
