package dev.atoms.demo.source;

import java.util.UUID;

/** Metadata is not authority: only the issuing store accepts this exact handle. */
record SourceCandidate(UUID id, UUID ownerId, UUID projectId, UUID taskId, UUID versionId, long revision, String treeSha256) {}
