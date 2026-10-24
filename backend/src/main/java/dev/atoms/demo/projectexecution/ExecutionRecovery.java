package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Startup-only metadata reconciliation under exclusive coordination. Never adopts or replays execution. */
final class ExecutionRecovery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PROBES = List.of("NODE", "NPM", "JAVA", "MAVEN");
    private static final List<String> OPERATIONS = List.of("NPM_CI", "TYPECHECK", "LINT", "UNIT_TEST", "FRONTEND_BUILD", "MAVEN_PACKAGE");
    private final ExecutionSettings settings;
    private final ExecutionSettings.Configuration configuration;
    private final ExecutionBuildRepository builds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionCoordinatorLease lease;
    private final BooleanSupplier sourceReady;

    ExecutionRecovery(ExecutionSettings settings, ExecutionBuildRepository builds, JdbcTemplate jdbc,
                      PlatformTransactionManager transactions, ExecutionCoordinatorLease lease, BooleanSupplier sourceReady) {
        this.settings = settings; this.configuration = settings.load(); this.builds = builds; this.jdbc = jdbc;
        this.lease = lease; this.sourceReady = sourceReady;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    /** Caller owns and checks the execution directory lease before/after this metadata-only operation. */
    ExecutionBuild reconcile(ExecutionBuild candidate) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            authority();
            return transaction.execute(tx -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, candidate.projectId());
                var row = jdbc.queryForMap("SELECT * FROM execution_builds WHERE project_id=? AND id=? FOR UPDATE",
                        candidate.projectId(), candidate.id());
                var current = builds.get(candidate.projectId(), candidate.id());
                if (!candidate.ownerId().equals(current.ownerId()) || !candidate.sourceVersionId().equals(current.sourceVersionId())
                        || !candidate.treeSha256().equals(current.treeSha256()) || !candidate.keySha256().equals(current.keySha256())
                        || !candidate.requestSha256().equals(current.requestSha256())) throw new IllegalStateException();
                authority();
                if (current.finishedAt() == null && !Set.of("QUEUED", "NEEDS_RECONCILIATION").contains(current.status())) {
                    // PACKAGING includes the seal/commit gap. A filename or digest cannot reconstruct a sealed capability.
                    boolean uncertain = Boolean.TRUE.equals(row.get("execution_pending")) || current.status().equals("PACKAGING")
                            || !completedHistory(current);
                    if (uncertain) {
                        jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION',error_code='EXECUTION_RECOVERY_UNCERTAIN' WHERE id=?", current.id());
                    } else {
                        jdbc.update("""
                                UPDATE execution_builds SET status=?,error_code=?,worker_slot=null,queue_slot=null,
                                    finished_at=clock_timestamp() WHERE id=?
                                """, current.cancelRequested() ? "CANCELLED" : "FAILED",
                                current.cancelRequested() ? "EXECUTION_CANCELLED" : "EXECUTION_INTERRUPTED", current.id());
                    }
                }
                // Loss of authority/configuration while waiting or writing rolls back this entire settlement.
                authority();
                return builds.get(current.projectId(), current.id());
            });
        } catch (RuntimeException error) { throw unavailable(); }
    }

    private boolean completedHistory(ExecutionBuild build) {
        var history = jdbc.queryForList("""
                SELECT payload::text FROM execution_build_events WHERE build_id=?
                    AND (payload->'probe' IS NOT NULL OR payload->'operation' IS NOT NULL
                        OR payload->'executionId' IS NOT NULL OR payload->'phase' IS NOT NULL)
                ORDER BY seq LIMIT 21
                """, String.class, build.id());
        if (history.size() > 20 || history.size() % 2 != 0) return false;
        int probes = 0, operations = 0;
        var ids = new HashSet<UUID>();
        try {
            for (int index = 0; index < history.size(); index += 2) {
                JsonNode start = JSON.readTree(history.get(index)), end = JSON.readTree(history.get(index + 1));
                if (!"started".equals(start.path("phase").asText()) || !"completed".equals(end.path("phase").asText())
                        || !start.has("code") || !start.get("code").isNull() || !end.has("code") || !end.get("code").isNull()
                        || !start.path("executionId").equals(end.path("executionId"))
                        || !ids.add(UUID.fromString(start.path("executionId").asText()))) return false;
                String field = start.has("probe") ? "probe" : "operation";
                String other = field.equals("probe") ? "operation" : "probe";
                if (start.has(other) || end.has(other) || !start.path(field).equals(end.path(field))) return false;
                if (field.equals("probe")) {
                    if (operations != 0 || probes >= PROBES.size() || !PROBES.get(probes++).equals(start.path(field).asText())) return false;
                } else if (operations >= OPERATIONS.size() || !OPERATIONS.get(operations++).equals(start.path(field).asText())) return false;
            }
            return switch (build.status()) {
                case "PREPARING" -> operations == 0;
                case "INSTALLING" -> operations == 1;
                case "CHECKING" -> operations >= 2 && operations <= 5;
                default -> false;
            };
        } catch (IOException | IllegalArgumentException error) { return false; }
    }

    private void authority() {
        if (!lease.healthy() || !sourceReady.getAsBoolean() || !configuration.equals(settings.load())) throw new IllegalStateException();
    }
    private static IOException unavailable() { return new IOException("EXECUTION_RECOVERY_UNAVAILABLE"); }
}
