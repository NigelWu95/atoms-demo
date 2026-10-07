package dev.atoms.demo.project;

import java.time.Instant;
import java.util.UUID;

public record Project(UUID id, String name, UUID ownerId, long projectRevision, UUID currentVersionId, Instant createdAt, Instant updatedAt, UUID activeRunId) {}
