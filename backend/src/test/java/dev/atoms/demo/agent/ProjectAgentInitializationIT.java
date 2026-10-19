package dev.atoms.demo.agent;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentInitializationIT extends AgentIntegrationSupport {
    @Autowired DataSource database;
    private ProjectAgentService tasks;
    private ProjectAgentInitialization initialization;

    @BeforeEach void services() {
        tasks = new ProjectAgentService(settings, new AgentTaskRepository(jdbc), projects, source.gateway(), transactions);
        initialization = new ProjectAgentInitialization(tasks, projects, source.gateway());
    }

    @Test void initializesRealTemplateThenExplicitlyAdmitsRequirementAgainstThatVersion() throws Exception {
        source.start(database);
        UUID project = source.createEmpty(owner);
        var initial = initialization.initialize(owner, project, "native-first");
        var result = initial.completion().get(30, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.templateId()).isEqualTo("full-project-v1");
        assertThat(result.templateDigest()).isEqualTo(new FullProjectTemplateCatalog().load().sha256());
        assertThat(new String(source.file(owner, project, result.resultVersionId(), "frontend/src/App.vue"), java.nio.charset.StandardCharsets.UTF_8))
                .contains("尚未生成业务功能");
        assertThat(initialization.initialize(owner, project, "native-first").completion().get(5, TimeUnit.SECONDS).id()).isEqualTo(result.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tasks WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(projects.get(owner, project.toString()).currentVersionId()).isNull();
        assertThat(temporary.resolve("agent-state")).doesNotExist();
        var task = tasks.submit(owner, project, new ProjectAgentService.Input("实现任务列表", result.resultVersionId().toString(), 1), "first-request");
        assertThat(task.status()).isEqualTo("QUEUED");
        assertThat(task.baseSourceVersionId()).isEqualTo(result.resultVersionId());
        assertThat(task.liveReserved()).isFalse();
        tasks.cancel(owner, project, task.id());
    }

    @Test void invalidSecondStepRetainsTheInitializedVersionAndCreatesNoTask() throws Exception {
        source.start(database);
        UUID project = source.createEmpty(owner);
        var initial = initialization.initialize(owner, project, "keep-initial").completion().get(30, TimeUnit.SECONDS);
        assertThat(initial.status()).isEqualTo("COMMITTED");
        assertThatThrownBy(() -> tasks.submit(owner, project, new ProjectAgentService.Input("", initial.resultVersionId().toString(), 1), "bad-request"))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT current_source_version_id FROM project_sources WHERE project_id=?", UUID.class, project))
                .isEqualTo(initial.resultVersionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_versions WHERE project_id=?", Integer.class, project)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tasks WHERE project_id=?", Integer.class, project)).isZero();
    }

    @Test void disabledForeignArchivedAndBrokenTemplateCreateNoIntentOrTask() {
        UUID project = source.createEmpty(owner);
        var reads = new AtomicInteger();
        var broken = new ProjectAgentInitialization(tasks, projects, source.gateway(), () -> {
            reads.incrementAndGet(); throw new IllegalStateException("synthetic catalog failure");
        });
        assertThatThrownBy(() -> broken.initialize(stranger, project, "foreign")).isInstanceOf(ApiException.class);
        configuration.setProperty("atoms.agent.maintainer-ids", owner + "," + stranger);
        assertThatThrownBy(() -> broken.initialize(stranger, project, "foreign-owner")).isInstanceOf(ApiException.class);
        projects.archive(owner, project.toString(), true);
        assertThatThrownBy(() -> broken.initialize(owner, project, "archived")).isInstanceOf(ApiException.class);
        projects.archive(owner, project.toString(), false);
        configuration.setProperty("atoms.agent.enabled", "false");
        assertThatThrownBy(() -> broken.initialize(owner, project, "disabled")).isInstanceOf(ApiException.class);
        assertThat(reads).hasValue(0);
        configuration.setProperty("atoms.agent.enabled", "true");
        assertThatThrownBy(() -> broken.initialize(owner, project, "bad-template")).isInstanceOf(IllegalStateException.class);
        assertThat(reads).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_operations WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tasks WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(temporary.resolve("agent-state")).doesNotExist();
    }

    @Test void callerTransactionIsRejectedBeforeReadingTemplateResources() {
        UUID project = source.createEmpty(owner);
        var reads = new AtomicInteger();
        var observed = new ProjectAgentInitialization(tasks, projects, source.gateway(), () -> {
            reads.incrementAndGet(); throw new IllegalStateException("template was read inside a transaction");
        });
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> observed.initialize(owner, project, "outer-transaction")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Initialization requires no outer transaction");
        assertThat(reads).hasValue(0);
    }
}
