package dev.atoms.demo.run;

import java.time.Instant;
import java.util.UUID;

public record Run(UUID id, UUID projectId, UUID ownerId, String prompt, String intent, UUID baseVersionId,
                  long expectedProjectRevision, String status, String stage, UUID resultVersionId,
                  String errorCode, String errorMessage, long lastEventSeq, Instant createdAt, Instant startedAt) {
    public boolean active() { return "QUEUED".equals(status) || "RUNNING".equals(status); }
}
