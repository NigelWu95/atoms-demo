package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Internal persisted task. Prompt is intentionally absent from toString and public task views. */
record AgentTask(UUID id, UUID projectId, UUID ownerId, UUID editLeaseId, UUID baseVersionId, long baseRevision,
                 String keySha256, String requestSha256, String prompt, String status, boolean cancelRequested,
                 UUID executionId, UUID sourceOperationId, UUID resultVersionId, boolean liveReserved, String errorCode,
                 int maxTokens, int maxTurns, int taskTimeoutSeconds, int queueTimeoutSeconds,
                 Instant createdAt, Instant startedAt, Instant finishedAt, String entryPoint) {
    static final Set<String> TERMINAL = Set.of("SOURCE_SAVED", "NO_CHANGES", "FAILED", "CANCELLED", "TIMED_OUT");
    boolean terminal() { return TERMINAL.contains(status); }
    @Override public String toString() { return "AgentTask[id=" + id + ",status=" + status + "]"; }

    record Request(String prompt, UUID base, long revision, String key, String digest,
                   int tokens, int turns, int taskSeconds, int queueSeconds) {
        @Override public String toString() { return "AgentRequest[base=" + base + ",revision=" + revision + "]"; }
    }

    static Request request(String prompt, String base, long revision, String key, AgentSettings.Configuration settings) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 8000 || prompt.indexOf('\0') >= 0
                || revision < 1 || revision > 9007199254740991L || key == null || key.isBlank()
                || key.length() > 128 || key.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        final UUID version;
        try { version = UUID.fromString(base); if (!version.toString().equals(base)) throw invalid(); }
        catch (IllegalArgumentException | NullPointerException error) { throw invalid(); }
        int taskSeconds = Math.toIntExact(settings.taskTimeout().toSeconds());
        int queueSeconds = Math.toIntExact(settings.queueTimeout().toSeconds());
        String digest = sha256(String.join("\n", "project-agent-v1", "claude-code-2.1.302", "deepseek-flash[1m]",
                Integer.toString(settings.maxOutputTokens()), Integer.toString(settings.maxTurns()),
                Integer.toString(taskSeconds), Integer.toString(queueSeconds), base, Long.toString(revision), prompt));
        return new Request(prompt, version, revision, sha256(key), digest, settings.maxOutputTokens(), settings.maxTurns(), taskSeconds, queueSeconds);
    }

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ApiException invalid() { return new ApiException(422, "AGENT_INVALID_INPUT", "需求、源码版本、修订号或幂等键无效。"); }
    static ApiException conflict(String code) { return new ApiException(409, code, "完整工程任务未执行，请刷新状态后重试。"); }
}
