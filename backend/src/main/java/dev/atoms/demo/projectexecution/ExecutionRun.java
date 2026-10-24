package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Immutable metadata snapshot. A row or PID is never a capability to own or stop a process. */
record ExecutionRun(UUID id, UUID projectId, UUID ownerId, UUID buildId, UUID sourceVersionId, UUID bindingId, UUID migrationReviewId,
        String treeSha256, String jarSha256, String manifestSha256, String bindingIdentitySha256, String keySha256, String requestSha256,
        String status, boolean stopRequested, UUID executionId, boolean executionPending, String stoppedSha256, String errorCode,
        Instant createdAt, Instant readyAt, Instant finishedAt) {
    record Request(UUID build, UUID binding, UUID review, String keyHash, String bodyHash) {}

    static Request request(UUID build, UUID binding, UUID review, String key) {
        if (build == null || binding == null || key == null || key.isBlank() || key.length() > 128
                || key.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff))
            throw error(422, "RUN_INVALID_INPUT");
        return new Request(build, binding, review, hash(key), bodyHash(build, binding, review));
    }
    static boolean valid(Request request) {
        return request != null && request.build() != null && request.binding() != null && request.keyHash() != null
                && request.keyHash().matches("[a-f0-9]{64}") && bodyHash(request.build(), request.binding(), request.review()).equals(request.bodyHash());
    }
    private static String bodyHash(UUID build, UUID binding, UUID review) { return hash("project-execution-run-v1\n" + build + "\n" + binding + "\n" + review); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ApiException error(int status, String code) { return new ApiException(status, code, "应用运行请求与当前项目状态不符，请刷新状态。"); }
}
