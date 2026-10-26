package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.ProjectExecutionAccess;
import dev.atoms.demo.source.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Deterministic classic STORED ZIP of an authorized immutable snapshot, with Unix file modes. */
final class DeliveryZip {
    static final int MAX_BYTES = 20 << 20, METADATA_BYTES = 512 << 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] README = """
            # 固定源码快照

            project/ 是 manifest.json 所标识的完整 Git 源码树，文件字节及执行权限保持不变。
            delivery/ 是独立的交付说明，不属于应用源码树。manifest.json 记录构建身份及逐文件 SHA-256。
            此包不包含 JAR、依赖缓存、平台账号/对话、业务数据、真实配置或密钥。

            请先阅读 project/README.md 并审阅代码和依赖，再从 project/ 执行其自带构建与启动脚本。
            需要数据库的新版工程使用外部私有测试配置构建，例如：
              node scripts/build.mjs --test-config /absolute/private/test.properties
            随后使用单独的运行配置启动：
              node scripts/start.mjs --config /absolute/private/runtime.properties
            旧快照未被修改；可用参数和工具版本以该快照自身脚本及说明为准。

            源码相同不代表运行数据库中已有数据相同。恢复源码不回滚数据库，也不自动构建、启动或发布。
            重新构建的 JAR 不承诺二进制相同；平台构建身份用于确认来源，不是下载目录的运行身份。
            """.getBytes(StandardCharsets.UTF_8);

    private DeliveryZip() {}
    record Manifest(int formatVersion, ProjectExecutionAccess.BuildIdentity build,
            ProjectExecutionAccess.RunningIdentity preview, String treeSha256, long totalBytes, List<FileView> files) {}
    record FileView(String path, int bytes, String sha256, String mode) {}
    private record Entry(byte[] name, int size, int mode, long crc, int offset) {}

    /** Authorization and exact build/run revalidation remain the caller's responsibility before returning these bytes. */
    static byte[] create(ProjectSourceReadGateway.Snapshot snapshot, ProjectExecutionAccess.BuildIdentity build,
            ProjectExecutionAccess.RunningIdentity preview, SourceExecutionBudget budget) {
        try {
            check(budget);
            var limits = ProjectSourcePolicy.DEFAULTS;
            if (snapshot.tree().files().size() > limits.files()) throw tooLarge();
            long sourceBytes = 0;
            for (var file : snapshot.tree().files()) {
                check(budget);
                if (file.size() > limits.fileBytes() || (sourceBytes += file.size()) > limits.treeBytes()) throw tooLarge();
            }
            var tree = new ProjectSourcePolicy().validate(snapshot.tree().files());
            var version = snapshot.version();
            if (!tree.sha256().equals(snapshot.tree().sha256()) || tree.totalBytes() != snapshot.tree().totalBytes()
                    || !tree.sha256().equals(version.treeSha256()) || !tree.sha256().equals(build.treeSha256())
                    || tree.files().size() != version.fileCount() || tree.totalBytes() != version.treeBytes()
                    || !version.id().equals(build.sourceVersionId()) || !version.projectId().equals(build.projectId())
                    || build.buildId() == null || build.jarBytes() <= 0 || !build.jarSha256().matches("[a-f0-9]{64}")
                    || !build.manifestSha256().matches("[a-f0-9]{64}")
                    || preview != null && (preview.runId() == null || preview.executionId() == null || !preview.build().equals(build))) throw invalid();
            var entries = new TreeMap<String, SourceTree.File>();
            var files = new ArrayList<FileView>();
            for (var file : tree.files()) {
                check(budget); byte[] content = file.content();
                files.add(new FileView(file.path(), content.length, sha(content), Integer.toOctalString(file.mode())));
                entries.put("project/" + file.path(), file);
            }
            byte[] manifest = JSON.writeValueAsBytes(new Manifest(1, build, preview, tree.sha256(), tree.totalBytes(), List.copyOf(files)));
            if (manifest.length + README.length > METADATA_BYTES) throw tooLarge();
            entries.put("delivery/manifest.json", new SourceTree.File("delivery/manifest.json", manifest, 0100644));
            entries.put("delivery/README.md", new SourceTree.File("delivery/README.md", README, 0100644));
            long expectedBytes = 22;
            for (var item : entries.entrySet()) expectedBytes += 76L + 2L * item.getKey().getBytes(StandardCharsets.UTF_8).length + item.getValue().size();
            if (expectedBytes > MAX_BYTES) throw tooLarge();
            var out = new Output((int) expectedBytes); var directory = new ArrayList<Entry>();
            for (var item : entries.entrySet()) {
                check(budget); var file = item.getValue(); byte[] name = item.getKey().getBytes(StandardCharsets.UTF_8), content = file.content();
                var crc = new CRC32(); crc.update(content);
                var entry = new Entry(name, content.length, file.mode(), crc.getValue(), out.size()); directory.add(entry);
                out.number(0x04034b50L, 4); fields(out, entry); out.bytes(name); out.bytes(content);
            }
            int directoryOffset = out.size();
            for (var entry : directory) {
                check(budget);
                out.number(0x02014b50L, 4); out.number((3 << 8) | 20, 2); // Unix creator, version 2.0.
                fields(out, entry); out.number(0, 2); out.number(0, 2); out.number(0, 2);
                out.number((long) entry.mode() << 16, 4); out.number(entry.offset(), 4); out.bytes(entry.name());
            }
            int directoryBytes = out.size() - directoryOffset;
            out.number(0x06054b50L, 4); out.number(0, 2); out.number(0, 2);
            out.number(directory.size(), 2); out.number(directory.size(), 2);
            out.number(directoryBytes, 4); out.number(directoryOffset, 4); out.number(0, 2);
            check(budget); return out.toByteArray();
        } catch (ApiException error) { throw error; }
        catch (Exception invalid) { throw invalid(); }
    }
    // Shared local/central fields: version, UTF-8, STORED, 1980-01-01, CRC, lengths, name length, no extra.
    private static void fields(Output out, Entry entry) {
        out.number(20, 2); out.number(0x800, 2); out.number(0, 2); out.number(0, 2); out.number(33, 2);
        out.number(entry.crc(), 4); out.number(entry.size(), 4); out.number(entry.size(), 4);
        out.number(entry.name().length, 2); out.number(0, 2);
    }
    private static final class Output extends ByteArrayOutputStream {
        Output(int size) { super(size); }
        void number(long value, int bytes) {
            if (count > MAX_BYTES - bytes) throw tooLarge();
            for (int i = 0; i < bytes; i++) write((int) (value >>> (8 * i)) & 0xff);
        }
        void bytes(byte[] value) {
            if (value.length > MAX_BYTES - count) throw tooLarge();
            writeBytes(value);
        }
    }
    private static String sha(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
    private static void check(SourceExecutionBudget budget) {
        try {
            var remaining = budget.remaining();
            if (Thread.currentThread().isInterrupted() || remaining == null || remaining.isNegative() || remaining.isZero()) throw new IOException();
        } catch (IOException | RuntimeException expired) { throw new ApiException(504, "DELIVERY_TIMEOUT", "源码打包超时，未返回下载内容。"); }
    }
    private static ApiException invalid() { return new ApiException(409, "DELIVERY_SOURCE_INVALID", "固定源码与构建身份不一致或超过交付限制。"); }
    private static ApiException tooLarge() { return new ApiException(413, "DELIVERY_SOURCE_TOO_LARGE", "源码或交付包超过大小限制。"); }
}
