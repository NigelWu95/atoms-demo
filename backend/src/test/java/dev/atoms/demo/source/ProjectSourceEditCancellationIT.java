package dev.atoms.demo.source;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.execution.OwnedProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.source.ProjectSourceInitializationIT.file;
import static org.assertj.core.api.Assertions.*;

class ProjectSourceEditCancellationIT extends SourceIntegrationSupport {
    @Autowired DataSource database;
    UUID base;
    @BeforeEach void baseline() {
        var tree = new ProjectSourcePolicy().validate(List.of(file("README.md", "one")));
        base = new SourceWorker(service, repository, environment).execute(
                new SourceInitializer(service).request(owner, project, tree, "base"), tree).resultVersionId();
    }

    @Test void cancellationDoesNotWaitOnFileIoWhileHoldingTheProjectDatabaseLock() throws Exception {
        var gateway = gateway(null); var handle = gateway.claimEdit(owner, project, base, 1, UUID.randomUUID());
        var entered = new CountDownLatch(1); var unblock = new CountDownLatch(1); var first = new AtomicBoolean(true);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var copy = threads.submit(() -> {
                assertThatThrownBy(() -> gateway.prepareEdit(handle, () -> {
                    if (first.compareAndSet(true, false)) {
                        entered.countDown();
                        try { if (!unblock.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.IOException(); }
                    }
                    return Duration.ofSeconds(30);
                })).isInstanceOf(ApiException.class);
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var cancel = threads.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                projects.lock(owner, project.toString());
                return gateway.cancelEdit(owner, handle);
            }));
            assertThat(cancel.get(2, TimeUnit.SECONDS).executionPending()).isFalse();
            unblock.countDown(); copy.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> gateway.markExecutionStarted(handle, UUID.randomUUID())).isInstanceOf(ApiException.class);
            assertThat(repository.state(project).status()).isEqualTo("READY");
            gateway.releaseEdit(handle);
        } finally { unblock.countDown(); threads.shutdownNow(); }
    }

    @Test void cancellationBeforePublicationPreventsNewPointerButAfterCommitReturnsCommittedFact() throws Exception {
        for (boolean before : new boolean[] {true, false}) {
            var entered = new CountDownLatch(1); var unblock = new CountDownLatch(1);
            try (var coordinator = new SourceCoordinator(service, repository, environment, database, stage -> {
                if (before && stage == SourceWorker.Stage.BEFORE_PUBLISH) {
                    entered.countDown();
                    try { if (!unblock.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }
            })) {
                coordinator.start(); var gateway = gateway(coordinator);
                var handle = gateway.claimEdit(owner, project, base, 1, UUID.randomUUID()); gateway.prepareEdit(handle);
                var path = gateway.workPath(handle); Files.writeString(path.resolve("README.md"), before ? "cancelled" : "saved");
                var execution = UUID.randomUUID(); gateway.markExecutionStarted(handle, execution);
                gateway.confirmExecutionStopped(handle, stopped(handle.taskId(), execution, path));
                var submission = (ProjectSourceEditGateway.Submitted) gateway.sealAndAccept(handle, "cancel-"+before);
                if (before) {
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                    gateway.cancelEdit(owner, handle); unblock.countDown();
                    var result = submission.completion().get(20, TimeUnit.SECONDS);
                    assertThat(result.status()).isEqualTo("CANCELLED");
                    assertThat(repository.state(project).versionId()).isEqualTo(base);
                    assertThatThrownBy(() -> jdbc.update("UPDATE source_edit_leases SET cancel_requested=false WHERE id=?", handle.leaseId()))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                } else {
                    var saved = submission.completion().get(20, TimeUnit.SECONDS);
                    assertThat(saved.status()).isEqualTo("COMMITTED");
                    assertThat(gateway.cancelEdit(owner, handle).operation()).isEqualTo(saved);
                    assertThat(gateway.operationFact(handle)).isEqualTo(saved);
                    assertThat(repository.state(project).versionId()).isEqualTo(saved.resultVersionId());
                }
                gateway.releaseEdit(handle);
            } finally { unblock.countDown(); }
        }
    }

    private ProjectSourceEditGateway gateway(SourceCoordinator coordinator) {
        return new ProjectSourceEditGateway(service, repository, environment, transactions,
                (java.util.function.Supplier<SourceCoordinator>) () -> coordinator);
    }
    private OwnedProcess.Stopped stopped(UUID task, UUID execution, Path work) throws Exception {
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), "dev.atoms.demo.agent.AgentChildFixture", "success")
                .directory(work.toFile()); command.environment().clear();
        return new OwnedProcess().run(command, "fixture", () -> Duration.ofSeconds(10), () -> false, task, execution,
                stream -> stream.readNBytes(2048)).stopped();
    }
}
