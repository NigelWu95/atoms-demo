package dev.atoms.demo.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Startup reconciliation only: unexplained candidates are never promoted or deleted. */
final class SourceRecovery {
    private final ProjectSourceService service;
    private final SourceRepository repository;
    private final ProjectSourceEnvironment environment;
    SourceRecovery(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment) {
        this.service = service; this.repository = repository; this.environment = environment;
    }

    SourceOperation reconcile(UUID project, UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Recovery I/O cannot run in a transaction");
        var operation = repository.operation(project, id);
        if (operation == null) throw ProjectSourceService.conflict("SOURCE_OPERATION_MISSING");
        if (operation.status().equals("FAILED") || operation.status().equals("CANCELLED")) return operation;
        try {
            var settings = environment.settings();
            var store = new SourceGitStore(settings.paths(), new SourceGitProcess(settings.git()), new ProjectSourcePolicy());
            var state = repository.state(project);
            if (state.versionId() != null) validate(repository.version(project, state.versionId()), store);
            var version = repository.version(project, operation.candidateVersionId());
            if (operation.status().equals("COMMITTED")) {
                if (!operation.candidateVersionId().equals(operation.resultVersionId())) throw unsafe();
                validate(version, store);
                return operation;
            }
            // A candidate metadata row without the atomic COMMITTED record is not a replayable success.
            if (version != null) throw unsafe();
            var directory = settings.paths().root().resolve(project.toString());
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                String reference = store.reference(project, operation.candidateVersionId());
                if (reference != null && !store.read(project, operation.candidateVersionId()).sha256().equals(operation.candidateSha256()))
                    throw unsafe();
            }
            return service.recovered(operation);
        } catch (IOException | IllegalArgumentException | dev.atoms.demo.common.ApiException error) {
            var result = service.uncertain(operation);
            if (operation.status().equals("COMMITTED")) throw ProjectSourceEnvironment.unavailable();
            return result;
        }
    }

    private void validate(SourceVersion version, SourceGitStore store) throws IOException {
        if (version == null || !version.commitId().equals(store.reference(version.projectId(), version.id()))) throw unsafe();
        SourceTree tree = store.read(version.projectId(), version.id());
        if (!tree.sha256().equals(version.treeSha256()) || tree.totalBytes() != version.treeBytes() || tree.files().size() != version.fileCount())
            throw unsafe();
    }
    private static IOException unsafe() { return new IOException("SOURCE_STORAGE_UNAVAILABLE"); }
}
