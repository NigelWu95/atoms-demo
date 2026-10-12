package dev.atoms.demo.workspace;

import java.time.Instant;
import java.util.UUID;

/** Public, path-free descriptor. Execution ownership remains repository-internal. */
public record EngineeringBuild(UUID id, UUID projectId, UUID versionId, String status, String stage,
        String templateDigest, String buildPolicyVersion, String bundleId, String archiveSha256,
        String loadedRuntimeId, String jarSha256, String errorCode, String errorMessage,
        Instant createdAt, Instant startedAt, Instant finishedAt, long lastEventSeq) {
    public boolean active() { return java.util.Set.of("QUEUED", "BUILDING", "CANCELLING").contains(status); }
    static String errorMessage(String code) {
        if (code == null) return null;
        return switch (code) {
            case "BUILD_CANCELLED" -> "构建已取消，已保存版本不受影响";
            case "BUILD_TIMEOUT" -> "构建超过十分钟，请检查环境后重新提交";
            case "BUILD_INPUT_CHANGED" -> "工程模板发生变化，请重新提交";
            case "BUILD_INTERRUPTED" -> "构建中断；需管理员核实执行环境，不会自动重跑";
            case "BUILD_ENVIRONMENT_NOT_READY" -> "本机构建环境未就绪";
            case "BUILD_CAPACITY_EXCEEDED" -> "本机构建存储达到上限";
            case "BUILD_ARTIFACT_UNAVAILABLE" -> "构建产物不可用或完整性校验失败";
            default -> "构建检查未通过，已保存版本不受影响";
        };
    }
}
