package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.util.*;

/** Fixed, bounded evidence schema: no caller-supplied paths, messages, versions or environment strings. */
final class ExecutionArtifactManifest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private record Manifest(int format, UUID buildId, UUID projectId, UUID sourceVersionId, String treeSha256,
                            ExecutionArtifact.Evidence evidence, long jarBytes, String jarSha256) {}

    static void validate(ExecutionBuild build, ExecutionArtifact.Evidence evidence) throws IOException {
        try {
            Objects.requireNonNull(build.id()); Objects.requireNonNull(build.projectId());
            Objects.requireNonNull(build.sourceVersionId()); hash(build.treeSha256());
            if (build.cancelRequested()) throw new IOException("EXECUTION_CANCELLED");
            if (!evidence.tools().keySet().equals(EnumSet.allOf(ExecutionToolchain.Kind.class))) throw invalid();
            for (var tool : evidence.tools().values()) {
                hash(tool.sha256()); count(tool.files(), 20000); count(tool.bytes(), 1L << 30);
            }
            var npm = evidence.npm(); var maven = evidence.maven();
            hash(npm.manifestSha256()); hash(npm.lockSha256()); hash(npm.policySha256()); count(npm.packages(), 4095);
            hash(maven.pomSha256()); hash(maven.policySha256());
            if (!npm.policySha256().equals(maven.policySha256())) throw invalid();
            var frontend = evidence.frontend(); var java = evidence.javaTests(); var resources = evidence.resources();
            count(frontend.files(), 512); count(frontend.suites(), 10000); count(frontend.tests(), 10000);
            count(java.files(), 512); count(java.tests(), 10000);
            if (frontend.suites() < frontend.files() || frontend.tests() < frontend.files() || java.tests() < java.files()) throw invalid();
            count(resources.files(), 1024); count(resources.bytes(), 32L << 20); hash(resources.sha256());
        } catch (RuntimeException error) { throw invalid(); }
    }

    static byte[] bytes(ExecutionBuild build, ExecutionArtifact.Evidence evidence, long size, String digest) throws IOException {
        validate(build, evidence); count(size, ExecutionArtifact.MAX_JAR_BYTES); hash(digest);
        byte[] bytes = JSON.writeValueAsBytes(new Manifest(1, build.id(), build.projectId(), build.sourceVersionId(),
                build.treeSha256(), evidence, size, digest));
        if (bytes.length > 16384) throw invalid();
        return bytes;
    }
    private static void count(long value, long max) throws IOException { if (value <= 0 || value > max) throw invalid(); }
    private static void hash(String value) throws IOException { if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid(); }
    private static IOException invalid() { return new IOException("EXECUTION_ARTIFACT_INVALID"); }
}
