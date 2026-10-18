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
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source reads cannot hold a database transaction during Git I/O");
        service.authorize(owner, project);
        var version = repository.version(project, versionId);
        if (version == null) throw new ApiException(404, "SOURCE_VERSION_MISSING", "源码版本不存在。");
        SourceTree tree;
        try {
            var settings = environment.existingSettings();
            var store = new SourceGitStore(settings.paths(), new SourceGitProcess(settings.git()), new ProjectSourcePolicy());
            if (!version.commitId().equals(store.reference(project, versionId))) throw new IOException("Source reference mismatch");
            tree = store.read(project, versionId);
            if (!version.treeSha256().equals(tree.sha256()) || version.fileCount() != tree.files().size()
                    || version.treeBytes() != tree.totalBytes()) throw new IOException("Source metadata mismatch");
        } catch (IOException | IllegalArgumentException | ApiException error) {
            service.storageUnavailable(owner, project);
            throw ProjectSourceEnvironment.unavailable();
        }
        service.authorize(owner, project);
        return new Snapshot(version, tree);
    }

    List<Entry> tree(UUID owner, UUID project, UUID version) {
        return snapshot(owner, project, version).tree().files().stream()
                .map(file -> new Entry(file.path(), file.mode(), file.size(), hash(file.content()))).toList();
    }

    SourceDiff.Result diff(UUID owner, UUID project, UUID before, UUID after) {
        var oldTree = snapshot(owner, project, before).tree();
        var newTree = snapshot(owner, project, after).tree();
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
