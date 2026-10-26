package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import dev.atoms.demo.source.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DeliveryZipTest {
    @TempDir Path directory;
    private final UUID project = UUID.randomUUID(), version = UUID.randomUUID(), buildId = UUID.randomUUID();
    private final SourceExecutionBudget budget = () -> Duration.ofSeconds(30);

    @Test void deterministicZipPreservesAllBytesMetadataAndRealUnzipExecutableMode() throws Exception {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "original\r\n", 0100644),
                file("backend/mvnw", "#!/bin/sh\nexit 0\n", 0100755), file("src/说明.txt", "字节不变\n", 0100644)));
        var build = build(tree); var preview = new ProjectExecutionAccess.RunningIdentity(UUID.randomUUID(), UUID.randomUUID(), build);
        byte[] zip = DeliveryZip.create(snapshot(tree), build, preview, budget);
        assertThat(DeliveryZip.create(snapshot(tree), build, preview, budget)).isEqualTo(zip);
        var values = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
                assertThat(entry.getTimeLocal()).isEqualTo(LocalDateTime.of(1980, 1, 1, 0, 0));
                values.put(entry.getName(), input.readAllBytes());
            }
        }
        assertThat(values.keySet()).containsExactly("delivery/README.md", "delivery/manifest.json", "project/README.md", "project/backend/mvnw", "project/src/说明.txt");
        for (var file : tree.files()) assertThat(values.get("project/" + file.path())).isEqualTo(file.content());
        var manifest = new ObjectMapper().readTree(values.get("delivery/manifest.json"));
        assertThat(manifest.path("build").path("sourceVersionId").asText()).isEqualTo(version.toString());
        assertThat(manifest.path("preview").path("runId").asText()).isEqualTo(preview.runId().toString());
        assertThat(manifest.path("treeSha256").asText()).isEqualTo(tree.sha256());
        assertThat(manifest.path("files").get(1).path("mode").asText()).isEqualTo("100755");
        Path archive = directory.resolve("delivery.zip"); Files.write(archive, zip);
        var unzip = new ProcessBuilder("/usr/bin/unzip", "-qq", archive.toString(), "-d", directory.resolve("unpacked").toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("unzip.log").toFile()).start();
        try { assertThat(unzip.waitFor(5, TimeUnit.SECONDS)).isTrue(); assertThat(unzip.exitValue()).isZero(); }
        finally { if (unzip.isAlive()) { unzip.destroyForcibly(); unzip.waitFor(5, TimeUnit.SECONDS); } }
        assertThat(Files.isExecutable(directory.resolve("unpacked/project/backend/mvnw"))).isTrue();
        assertThat(Files.isExecutable(directory.resolve("unpacked/project/README.md"))).isFalse();
        for (var file : tree.files()) assertThat(Files.readAllBytes(directory.resolve("unpacked/project/" + file.path()))).isEqualTo(file.content());
    }

    @Test void fixedBuildDownloadHasNoPreviewIdentityAndDoesNotReadAnyCurrentTemplate() throws Exception {
        var tree = new ProjectSourcePolicy().validate(List.of(file("old-original.txt", "retained source\n", 0100644)));
        byte[] zip = DeliveryZip.create(snapshot(tree), build(tree), null, budget);
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                if (entry.getName().equals("delivery/manifest.json")) {
                    var manifest = new ObjectMapper().readTree(input.readAllBytes());
                    assertThat(manifest.path("preview").isNull()).isTrue(); assertThat(manifest.path("files").size()).isEqualTo(1);
                }
            }
        }
    }

    @Test void rejectsSourceDriftWrongRunAndUnsafeFilesBeforeReturningBytes() {
        var tree = new ProjectSourcePolicy().validate(List.of(file("ok.txt", "source", 0100644)));
        var drifted = new SourceTree(tree.files(), "0".repeat(64), tree.totalBytes());
        code("DELIVERY_SOURCE_INVALID", () -> DeliveryZip.create(snapshot(drifted), build(tree), null, budget));
        var wrong = new ProjectExecutionAccess.BuildIdentity(project, buildId, UUID.randomUUID(), tree.sha256(), 1, "a".repeat(64), "b".repeat(64));
        code("DELIVERY_SOURCE_INVALID", () -> DeliveryZip.create(snapshot(tree), build(tree),
                new ProjectExecutionAccess.RunningIdentity(UUID.randomUUID(), UUID.randomUUID(), wrong), budget));
        for (var file : List.of(file("../outside", "source", 0100644), file(".env", "source", 0100644), file("src/link", "target", 0120777))) {
            var unsafe = new SourceTree(List.of(file), tree.sha256(), file.size());
            code("DELIVERY_SOURCE_INVALID", () -> DeliveryZip.create(snapshot(unsafe), build(tree), null, budget));
        }
    }

    @Test void handlesMaximumTreeWithinZipLimitAndHonorsCancellationDuringPacking() {
        var files = new ArrayList<SourceTree.File>(); byte[] bytes = new byte[2 << 20]; Arrays.fill(bytes, (byte) 'a');
        for (int i = 0; i < 8; i++) files.add(new SourceTree.File("src/file-" + i + ".txt", bytes, 0100644));
        var tree = new ProjectSourcePolicy().validate(files);
        assertThat(DeliveryZip.create(snapshot(tree), build(tree), null, budget).length).isBetween(16 << 20, DeliveryZip.MAX_BYTES);
        var calls = new AtomicInteger();
        code("DELIVERY_TIMEOUT", () -> DeliveryZip.create(snapshot(tree), build(tree), null,
                () -> calls.incrementAndGet() < 13 ? Duration.ofSeconds(30) : Duration.ZERO));
        var tooLarge = new SourceTree(List.of(new SourceTree.File("large.txt", new byte[(2 << 20) + 1], 0100644)), tree.sha256(), (2 << 20) + 1);
        code("DELIVERY_SOURCE_TOO_LARGE", () -> DeliveryZip.create(snapshot(tooLarge), build(tree), null, budget));
    }
    private SourceTree.File file(String name, String content, int mode) { return new SourceTree.File(name, content.getBytes(StandardCharsets.UTF_8), mode); }
    private ProjectExecutionAccess.BuildIdentity build(SourceTree tree) {
        return new ProjectExecutionAccess.BuildIdentity(project, buildId, version, tree.sha256(), 123, "a".repeat(64), "b".repeat(64));
    }
    private ProjectSourceReadGateway.Snapshot snapshot(SourceTree tree) {
        return new ProjectSourceReadGateway.Snapshot(new SourceVersion(version, project, 1, "c".repeat(40), tree.sha256(),
                tree.files().size(), tree.totalBytes(), null, null, null, "sample", "d".repeat(64), Instant.EPOCH), tree);
    }
    private void code(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code)).hasNoCause();
    }
}
