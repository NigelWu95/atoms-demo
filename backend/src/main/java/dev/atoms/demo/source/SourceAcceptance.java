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
        return prepare(owner, project, candidate, key, null, null);
    }

    Prepared prepare(UUID owner, UUID project, SourceCandidate candidate, String key,
                     SourceEditLeaseRepository.Lease permit, SourceDeadline deadline) {
        // Multiple adapter instances can share the issuing store. Consume the capability only once.
        synchronized (candidates) { return prepareOnce(owner, project, candidate, key, permit, deadline); }
    }

    private Prepared prepareOnce(UUID owner, UUID project, SourceCandidate candidate, String key,
                                 SourceEditLeaseRepository.Lease permit, SourceDeadline deadline) {
        if (candidate == null || !owner.equals(candidate.ownerId()) || !project.equals(candidate.projectId()))
            throw ProjectSourceService.conflict("SOURCE_CANDIDATE_UNAVAILABLE");
        var request = new SourceRequest("ACCEPT", candidate.versionId(), candidate.revision(), null, null, null, null, candidate.treeSha256());
        var previous = service.replay(owner, project, request, key, permit);
        if (previous != null) return new Prepared(previous, null);
        if (deadline != null) {
            try { deadline.remaining(java.time.Duration.ofSeconds(300)); }
            catch (SourceDeadline.Expired error) { throw new ApiException(408, "SOURCE_OPERATION_TIMEOUT", "源码操作超时。"); }
        }
        candidates.authorize(owner, project, candidate);
        var operation = service.admit(owner, project, request, key, permit);
        // Return persisted retries before reading an obsolete baseline or touching staged files.
        if (!operation.status().equals("PREPARING") || operation.executionId() != null) return new Prepared(operation, null);
        try { return new Prepared(operation, deadline == null ? candidates.read(owner, project, candidate)
                : candidates.read(owner, project, candidate, deadline)); }
        catch (ApiException error) {
            String code = error.code().startsWith("SOURCE_") ? error.code() : "SOURCE_ACCESS_CHANGED";
            return new Prepared(service.fail(project, operation.id(), code, false), null);
        } finally { candidates.release(candidate); }
    }

    /** tree is present only for an operation that can be queued for execution. */
    record Prepared(SourceOperation operation, SourceTree tree) {}
}
