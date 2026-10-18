package dev.atoms.demo.source;

import java.util.UUID;

/** Internal capability. The issuing store must recognize this exact handle; identifiers alone confer no authority. */
final class SourceWorkspace {
    private final UUID ownerId, projectId, taskId, versionId;
    private final long revision;
    private final String treeSha256;

    SourceWorkspace(UUID ownerId, UUID projectId, UUID taskId, UUID versionId, long revision, String treeSha256) {
        this.ownerId = ownerId; this.projectId = projectId; this.taskId = taskId;
        this.versionId = versionId; this.revision = revision; this.treeSha256 = treeSha256;
    }
    UUID ownerId() { return ownerId; }
    UUID projectId() { return projectId; }
    UUID taskId() { return taskId; }
    UUID versionId() { return versionId; }
    long revision() { return revision; }
    String treeSha256() { return treeSha256; }
    @Override public String toString() { return "SourceWorkspace[taskId=" + taskId + "]"; }
}
