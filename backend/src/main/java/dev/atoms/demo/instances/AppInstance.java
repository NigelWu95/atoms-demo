package dev.atoms.demo.instances;

import java.time.Instant;
import java.util.UUID;

public record AppInstance(UUID id, UUID projectId, UUID buildId, UUID versionId, String status,
        String loadedRuntimeId, String errorCode, Instant createdAt, Instant startedAt, Instant finishedAt) {}
