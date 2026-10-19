package dev.atoms.demo.agent;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Erases terminal task bodies, never task identity, source history, disk evidence or unresolved work. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="atoms.agent.enabled", havingValue="true")
final class AgentRetention {
    private final AgentSettings settings;
    private final JdbcTemplate jdbc;
    private final AgentTaskRepository tasks;
    private final TransactionTemplate transaction;

    AgentRetention(AgentSettings settings, JdbcTemplate jdbc, AgentTaskRepository tasks,
                   PlatformTransactionManager transactions) {
        this.settings = settings; this.jdbc = jdbc; this.tasks = tasks;
        this.transaction = new TransactionTemplate(transactions);
    }

    // Same fixed-delay scheduler as existing maintenance; disabled Agent has no scheduled bean.
    // https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-scheduled
    @Scheduled(fixedDelay=300000, initialDelay=60000)
    void clean() {
        if (!settings.enabled()) return;
        UUID run = UUID.randomUUID();
        try {
            int expired = expireBatch(100);
            if (expired > 0) LoggerFactory.getLogger(getClass()).info(
                    "{\"event\":\"agent_retention_completed\",\"entryPoint\":\"agent_retention_scheduler\",\"requestId\":\"{}\",\"tasks\":{}}", run, expired);
        } catch (DataAccessException error) {
            LoggerFactory.getLogger(getClass()).warn(
                    "{\"event\":\"agent_retention_failed\",\"entryPoint\":\"agent_retention_scheduler\",\"requestId\":\"{}\",\"errorCode\":\"AGENT_RETENTION_UNAVAILABLE\"}", run);
        }
    }

    int expireBatch(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("Invalid retention batch size");
        if (!settings.enabled()) return 0;
        var candidates = jdbc.query("""
                SELECT a.id FROM agent_tasks a JOIN source_edit_leases e ON e.id=a.edit_lease_id
                WHERE NOT a.history_expired AND a.finished_at<=clock_timestamp()-interval '30 days'
                  AND e.status='RELEASED' AND NOT e.execution_pending
                ORDER BY a.finished_at,a.id LIMIT ?
                """, (r,n) -> r.getObject(1, UUID.class), limit);
        int expired = 0;
        for (var id : candidates) {
            if (!settings.enabled()) break;
            expired += transaction.execute(status -> {
                // Match query/admission/publication lock order; never hold two projects at once.
                tasks.lock(id);
                int changed = jdbc.update("""
                        UPDATE agent_tasks SET history_expired=true,prompt=NULL,event_bytes=0
                        WHERE id=? AND NOT history_expired AND finished_at<=clock_timestamp()-interval '30 days'
                          AND EXISTS(SELECT 1 FROM source_edit_leases e WHERE e.id=edit_lease_id
                            AND e.status='RELEASED' AND NOT e.execution_pending)
                        """, id);
                if (changed == 1) jdbc.update("DELETE FROM agent_events WHERE task_id=?", id);
                return changed;
            });
        }
        return expired;
    }
}
