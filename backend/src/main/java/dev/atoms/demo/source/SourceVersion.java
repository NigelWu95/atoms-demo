package dev.atoms.demo.source;

import java.time.Instant;
import java.util.UUID;

public record SourceVersion(UUID id, UUID projectId, long versionNo, String commitId, String treeSha256,
        int fileCount, long treeBytes, UUID basedOnId, UUID restoredFromId, UUID legacyVersionId,
        String templateId, String templateDigest, Instant createdAt) {}
