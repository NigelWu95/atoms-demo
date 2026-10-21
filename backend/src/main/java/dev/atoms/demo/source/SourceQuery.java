package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal authorized reads. UUIDs address published versions, never arbitrary Git objects or server paths. */
final class SourceQuery {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SourceQuery.class);
    private final ProjectSourceService service;
    private final SourceRepository repository;
    private final ProjectSourceEnvironment environment;

    SourceQuery(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment) {
        this.service = service; this.repository = repository; this.environment = environment;
    }

    List<SourceVersion> versions(UUID owner, UUID project, Long beforeVersion, int limit) {
        service.authorize(owner, project);
        if (limit < 1 || limit > 100 || beforeVersion != null && beforeVersion < 1)
            throw new IllegalArgumentException("Invalid source page bounds");
        var result = repository.versions(project, beforeVersion, limit);
        service.authorize(owner, project);
        return result;
    }

    Snapshot snapshot(UUID owner, UUID project, UUID versionId) {
        return snapshot(owner, project, versionId, new SourceDeadline(environment.operationTimeout()));
    }

    Snapshot snapshot(UUID owner, UUID project, UUID versionId, SourceDeadline deadline) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source reads cannot hold a database transaction during Git I/O");
        service.authorize(owner, project);
        var version = repository.version(project, versionId);
        if (version == null) throw new ApiException(404, "SOURCE_VERSION_MISSING", "源码版本不存在。");
        SourceTree tree;
        long started = System.nanoTime();
        String stage = "ENVIRONMENT";
        try {
            var settings = environment.existingSettings();
            var process = new SourceGitProcess(settings.git(), java.time.Duration.ofSeconds(60), handle -> {},
                    deadline);
            var store = new SourceGitStore(settings.paths(), process, new ProjectSourcePolicy());
            stage = "REFERENCE";
            if (!version.commitId().equals(store.reference(project, versionId))) throw new IOException("Source reference mismatch");
            stage = "TREE";
            tree = store.read(project, versionId);
            stage = "METADATA";
            if (!version.treeSha256().equals(tree.sha256()) || version.fileCount() != tree.files().size()
                    || version.treeBytes() != tree.totalBytes()) throw new IOException("Source metadata mismatch");
        } catch (SourceDeadline.Expired error) {
            // A caller's expired read budget does not establish that immutable source storage is corrupt.
            throw new ApiException(408, "SOURCE_OPERATION_TIMEOUT", "源码操作已超时，请检查任务状态。");
        } catch (IOException | IllegalArgumentException | ApiException error) {
            // One bounded event per failed read; never log exceptions, source bytes, paths or raw Git output.
            LOG.warn("{\"event\":\"source_snapshot_failed\",\"entryPoint\":\"source_snapshot\",\"requestId\":\"{}\",\"projectId\":\"{}\",\"sourceVersionId\":\"{}\",\"stage\":\"{}\",\"failureKind\":\"{}\",\"durationMs\":{}}",
                    UUID.randomUUID(), project, versionId, stage, failureKind(error),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            service.storageUnavailable(owner, project);
            throw ProjectSourceEnvironment.unavailable();
        }
        service.authorize(owner, project);
        return new Snapshot(version, tree);
    }

    static String failureKind(Exception error) {
        if (error instanceof SourceGitProcess.GitFailure) {
            String code = error.getMessage();
            return code != null && java.util.Set.of("GIT_COMMAND_REJECTED", "GIT_UNAVAILABLE", "GIT_TIMEOUT",
                    "GIT_FAILED", "GIT_CANCELLED", "GIT_OUTPUT_LIMIT", "GIT_IO_FAILED", "GIT_IO_UNSETTLED",
                    "GIT_PROCESS_UNSETTLED").contains(code) ? code : "GIT_FAILURE";
        }
        if (error instanceof java.nio.channels.ClosedByInterruptException) return "INTERRUPTED_CHANNEL";
        if (error instanceof java.nio.file.NoSuchFileException) return "FILE_MISSING";
        if (error instanceof java.nio.file.AccessDeniedException) return "ACCESS_DENIED";
        if (error instanceof ApiException) return "ENVIRONMENT_UNAVAILABLE";
        if (error instanceof IllegalArgumentException) return "INVALID_STORAGE";
        return "IO_FAILURE";
    }

    List<Entry> tree(UUID owner, UUID project, UUID version) {
        return snapshot(owner, project, version).tree().files().stream()
                .map(file -> new Entry(file.path(), file.mode(), file.size(), hash(file.content()))).toList();
    }

    SourceDiff.Result diff(UUID owner, UUID project, UUID before, UUID after) {
        var deadline = new SourceDeadline(environment.operationTimeout());
        var oldTree = snapshot(owner, project, before, deadline).tree();
        var newTree = snapshot(owner, project, after, deadline).tree();
        var result = new SourceDiff().compare(oldTree, newTree);
        service.authorize(owner, project);
        return result;
    }

    SourceTree.File file(UUID owner, UUID project, UUID version, String path) {
        service.authorize(owner, project);
        new ProjectSourcePolicy().validatePath(path);
        return snapshot(owner, project, version).tree().files().stream().filter(file -> file.path().equals(path)).findFirst()
                .orElseThrow(() -> new ApiException(404, "SOURCE_FILE_MISSING", "源码文件不存在。"));
    }

    private static String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("Source digest unavailable", error); }
    }
    record Snapshot(SourceVersion version, SourceTree tree) {}
    record Entry(String path, int mode, long bytes, String sha256) {}
}
