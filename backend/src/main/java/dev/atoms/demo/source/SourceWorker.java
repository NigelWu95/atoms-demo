package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs only controlled snapshot I/O, never project build or application code. */
final class SourceWorker {
    enum Stage { BEFORE_GIT, GIT_STARTED, AFTER_GIT, BEFORE_PUBLISH, AFTER_COMMIT }
    private final ProjectSourceService service;
    private final SourceRepository repository;
    private final ProjectSourceEnvironment environment;
    private final Consumer<Stage> observer;
    SourceWorker(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment) {
        this(service, repository, environment, stage -> {});
    }
    SourceWorker(ProjectSourceService service, SourceRepository repository, ProjectSourceEnvironment environment, Consumer<Stage> observer) {
        this.service = service; this.repository = repository; this.environment = environment; this.observer = observer;
    }

    synchronized SourceOperation execute(SourceOperation requested, SourceTree input) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source I/O cannot run in a database transaction");
        try {
            SourceOperation operation = service.prepare(requested.projectId(), requested.id());
            if (operation.terminal()) return operation;
            SourceTree tree = new ProjectSourcePolicy().validate(input.files());
            if (!tree.sha256().equals(operation.candidateSha256()) || !tree.sha256().equals(input.sha256()) || tree.totalBytes() != input.totalBytes())
                throw ProjectSourceService.conflict("SOURCE_INPUT_CHANGED");
            observer.accept(Stage.BEFORE_GIT);
            var settings = environment.settings();
            var process = new SourceGitProcess(settings.git(), java.time.Duration.ofSeconds(60),
                    handle -> observer.accept(Stage.GIT_STARTED));
            var store = new SourceGitStore(settings.paths(), process, new ProjectSourcePolicy());
            operation = service.beginExecution(operation.projectId(), operation.id());
            if (operation.terminal()) return operation;
            final SourceGitStore.Stored stored;
            try {
                if (operation.expectedVersionId() == null
                        && !Files.exists(settings.paths().root().resolve(operation.projectId().toString()), LinkOption.NOFOLLOW_LINKS))
                    store.initialize(operation.projectId());
                stored = store.save(operation.projectId(), operation.candidateVersionId(), operation.expectedVersionId(), tree);
            } catch (IOException error) {
                // The Git adapter confirms exit in finally. Explicitly unsettled I/O/processes retain the durable fence.
                if (!(error instanceof SourceGitProcess.GitFailure && error.getMessage().contains("UNSETTLED")))
                    service.executionSettled(operation.projectId(), operation.id(), operation.executionId());
                throw error;
            }
            service.executionSettled(operation.projectId(), operation.id(), operation.executionId());
            observer.accept(Stage.AFTER_GIT);
            operation = service.ready(operation.projectId(), operation.id());
            if (operation.terminal()) return operation;
            observer.accept(Stage.BEFORE_PUBLISH);
            SourceOperation result = service.publish(operation.projectId(), operation.id(), stored, tree);
            observer.accept(Stage.AFTER_COMMIT);
            return result;
        } catch (ApiException error) {
            String code = error.code().startsWith("SOURCE_") ? error.code() : "SOURCE_ACCESS_CHANGED";
            return service.fail(requested.projectId(), requested.id(), code, code.equals("SOURCE_STORAGE_UNAVAILABLE"));
        } catch (IOException error) {
            return service.fail(requested.projectId(), requested.id(), "SOURCE_STORAGE_UNAVAILABLE", true);
        } catch (IllegalArgumentException error) {
            return service.fail(requested.projectId(), requested.id(), "SOURCE_INPUT_CHANGED", false);
        } catch (RuntimeException error) {
            // Unknown execution outcomes remain fenced. Do not log raw exceptions containing paths/source/config.
            return service.fail(requested.projectId(), requested.id(), "SOURCE_EXECUTION_UNCERTAIN", true);
        }
    }
}
