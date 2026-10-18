package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.source.SourceAgentFixture;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentAdmissionIT extends AgentIntegrationSupport {
    private ProjectAgentService service;
    private AgentTaskRepository repository;
    @BeforeEach void admission() {
        repository = new AgentTaskRepository(jdbc);
        service = new ProjectAgentService(settings, repository, projects, source.gateway(), transactions);
    }
    private ProjectAgentService.Input input(SourceAgentFixture.Base base, String prompt) {
        return new ProjectAgentService.Input(prompt, base.versionId().toString(), base.revision());
    }

    @Test void concurrentSameKeyCreatesOnlyOneTaskAndOneSourceLease() throws Exception {
        var base = source.create(owner); var threads = Executors.newFixedThreadPool(2);
        try {
            var one = threads.submit(() -> service.submit(owner, base.projectId(), input(base, "change"), "once"));
            var two = threads.submit(() -> service.submit(owner, base.projectId(), input(base, "change"), "once"));
            var a = one.get(20, TimeUnit.SECONDS); var b = two.get(20, TimeUnit.SECONDS);
            assertThat(a.id()).isEqualTo(b.id()); assertThat(a.status()).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, base.projectId())).isEqualTo(1);
            assertThat(repository.get(a.id()).liveReserved()).isFalse();
            assertThat(service.handle(a.id())).isNotNull();
            assertThatThrownBy(() -> service.submit(owner, base.projectId(), input(base, "different"), "once"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_IDEMPOTENCY_CONFLICT"));
            assertThatThrownBy(() -> service.submit(owner, base.projectId(), input(base, "change"), "new-key")).isInstanceOf(ApiException.class);
            assertThat(temporary.resolve("agent-state")).doesNotExist();
        } finally { threads.shutdownNow(); }
    }

    @Test void rejectsUnapprovedOwnerArchiveAndChangedOwnershipBeforeReturningAReplay() {
        var base = source.create(owner);
        assertThatThrownBy(() -> service.submit(stranger, base.projectId(), input(base, "private"), "first")).isInstanceOf(ApiException.class);
        var task = service.submit(owner, base.projectId(), input(base, "private"), "first");
        jdbc.update("UPDATE projects SET owner_id=? WHERE id=?", stranger, base.projectId());
        assertThatThrownBy(() -> service.get(owner, base.projectId(), task.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit(owner, base.projectId(), input(base, "private"), "first")).isInstanceOf(ApiException.class);
        var archived = source.create(owner); projects.archive(owner, archived.projectId().toString(), true);
        assertThatThrownBy(() -> service.submit(owner, archived.projectId(), input(archived, "private"), "first")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, archived.projectId())).isZero();
    }

    @Test void outerTransactionFailureRollsBackTaskLeaseAndInMemoryCapability() {
        var base = source.create(owner); var ids = new java.util.ArrayList<java.util.UUID>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ids.add(service.submit(owner, base.projectId(), input(base, "change"), "retry").id());
            status.setRollbackOnly();
        });
        assertThat(repository.get(ids.get(0))).isNull();
        assertThat(service.handle(ids.get(0))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, base.projectId())).isZero();
        assertThat(service.submit(owner, base.projectId(), input(base, "change"), "retry").id()).isNotEqualTo(ids.get(0));
    }

    @Test void queueIsGloballyBoundedWithoutLeakingANinthProjectLease() {
        for (int i=0; i<8; i++) {
            var base = source.create(owner);
            service.submit(owner, base.projectId(), input(base, "change"), "queued");
        }
        var ninth = source.create(owner);
        assertThatThrownBy(() -> service.submit(owner, ninth.projectId(), input(ninth, "change"), "overflow"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AGENT_QUEUE_FULL"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_edit_leases WHERE project_id=?", Integer.class, ninth.projectId())).isZero();
    }

    @Test void databaseRejectsTaskBindingToAnotherLeasesOwnerOrBaseline() {
        var base = source.create(owner);
        var task = service.submit(owner, base.projectId(), input(base, "private"), "bind");
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_tasks SET owner_id=? WHERE id=?", stranger, task.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_tasks SET base_revision=2 WHERE id=?", task.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM agent_tasks WHERE id=?", task.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.get(owner, base.projectId(), task.id()).toString()).doesNotContain("private");
    }
}
