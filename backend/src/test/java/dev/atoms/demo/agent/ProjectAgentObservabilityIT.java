package dev.atoms.demo.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentObservabilityIT extends AgentIntegrationSupport {
    private static final String PRIVATE = "PRIVATE_AUDIT_CANARY user@example.invalid <script>private source</script>";

    @Test void taskFailuresExposeStableFactsNotPromptOrAdapterDiagnostics() throws Exception {
        var tasks = new AgentTaskRepository(jdbc);
        var service = new ProjectAgentService(settings, tasks, projects, source.gateway(), transactions);
        var lifecycle = new AgentTaskLifecycle(service, tasks, projects, source.gateway(), transactions);
        var base = source.create(owner);
        var task = service.submit(owner, base.projectId(), new ProjectAgentService.Input(PRIVATE, base.versionId().toString(), 1), "audit");
        var logger = (Logger) LoggerFactory.getLogger(AgentWorker.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        var preparations = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var result = new AgentWorker(service, tasks, lifecycle, source.gateway(), (t, work, deadline) -> {
                preparations.incrementAndGet();
                throw new IOException(PRIVATE);
            }).execute(task.id());
            assertThat(preparations.get()).isEqualTo(1);
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(logs.list).hasSize(1);
            var event = new ObjectMapper().readTree(logs.list.get(0).getFormattedMessage());
            assertThat(event.path("requestId").asText()).isEqualTo(task.id().toString());
            assertThat(event.path("entryPoint").asText()).isEqualTo("internal_service");
            assertThat(event.path("durationMs").asLong()).isNotNegative();
            assertThat(logs.list.get(0).getThrowableProxy()).isNull();
            var query = new AgentQuery(settings, projects, tasks, new AgentEventRepository(jdbc), transactions);
            assertThat(event.toString()+result+query.events(owner, base.projectId(), task.id(), 0, 100))
                    .doesNotContain("PRIVATE_AUDIT_CANARY", "user@example.invalid", "private source");
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void cleanupFailureDoesNotLogDatabaseExceptionTextOrSqlDiagnostics() throws Exception {
        var broken = new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                throw new DataAccessResourceFailureException(PRIVATE);
            }
        };
        var logger = (Logger) LoggerFactory.getLogger(AgentRetention.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            new AgentRetention(settings, broken, new AgentTaskRepository(jdbc), transactions).clean();
            assertThat(logs.list).hasSize(1);
            var event = new ObjectMapper().readTree(logs.list.get(0).getFormattedMessage());
            assertThat(event.path("event").asText()).isEqualTo("agent_retention_failed");
            assertThat(event.path("entryPoint").asText()).isEqualTo("agent_retention_scheduler");
            assertThat(event.path("requestId").asText()).matches("[0-9a-f-]{36}");
            assertThat(event.toString()).doesNotContain("PRIVATE_AUDIT_CANARY", "user@example.invalid", "private source");
            assertThat(logs.list.get(0).getThrowableProxy()).isNull();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
}
