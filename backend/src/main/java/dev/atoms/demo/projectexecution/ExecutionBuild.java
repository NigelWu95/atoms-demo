package dev.atoms.demo.projectexecution;

import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Internal metadata only; neither source bytes nor execution credentials belong here. */
record ExecutionBuild(UUID id, UUID projectId, UUID ownerId, UUID sourceVersionId, String treeSha256,
                      String keySha256, String requestSha256, String status, boolean cancelRequested,
                      String errorCode, Instant createdAt, Instant finishedAt, int lastEventSeq) {
    record Request(UUID version, String keyHash, String bodyHash) {}

    static Request request(UUID version, String key) {
        if (version == null || key == null || key.isBlank() || key.length() > 128
                || key.codePoints().anyMatch(value -> Character.isISOControl(value) || (value >= 0xd800 && value <= 0xdfff)))
            throw new ApiException(422, "EXECUTION_INVALID_INPUT", "源码版本或幂等键无效。");
        return new Request(version, sha256(key), sha256("project-execution-build-v1\n" + version));
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static ApiException conflict(String code) {
        return new ApiException(409, code, "构建请求与已有任务冲突，请刷新状态。");
    }
}
