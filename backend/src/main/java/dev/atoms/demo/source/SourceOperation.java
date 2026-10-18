package dev.atoms.demo.source;

import java.time.Instant;
import java.util.UUID;

public record SourceOperation(UUID id, UUID projectId, UUID ownerId, String kind, String idempotencySha256,
        String requestSha256, UUID expectedVersionId, long expectedRevision, UUID candidateVersionId,
        UUID targetVersionId, UUID legacyVersionId, String templateId, String templateDigest, String candidateSha256,
        String status, boolean cancelRequested, UUID resultVersionId, String errorCode, Instant createdAt, Instant finishedAt,
        UUID executionId, boolean executionPending) {
    public boolean terminal() { return java.util.Set.of("COMMITTED", "FAILED", "CANCELLED").contains(status); }
}
