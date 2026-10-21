package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.execution.OwnedProcess;
import dev.atoms.demo.project.ProjectService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;

/** Internal settlement only. Never launches, retries, adopts a process, or reconstructs a sealed handle. */
final class ExecutionBuildSettlement {
    private static final List<ExecutionToolchain.Operation> ORDER = List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD, MAVEN_PACKAGE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final ExecutionBuildRepository builds;
    private final TransactionTemplate transaction;

    ExecutionBuildSettlement(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
                             PlatformTransactionManager transactions) {
        this.settings = settings; this.projects = projects; this.jdbc = jdbc;
        builds = new ExecutionBuildRepository(jdbc);
        // Keep hashing and file verification outside the short metadata transaction.
        // https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
    }

    ExecutionBuild succeed(ExecutionBuild build, ExecutionArtifact artifact, OwnedProcess.Budget budget) throws IOException {
        outsideTransaction();
        if (build == null || artifact == null || budget == null) throw unavailable();
        var evidence = artifact.evidenceFor(build);
        artifact.verify(budget);
        var digest = artifact.summary();
        return transact(() -> {
            if (projects.lock(build.ownerId(), build.projectId().toString()).archived()) reject();
            var state = locked(build);
            // A blocked lock acquisition must not preserve a permission snapshot from before the wait.
            if (!settings.load().maintainerIds().contains(build.ownerId())) reject();
            if (!"PACKAGING".equals(state.get("status")) || Boolean.TRUE.equals(state.get("execution_pending"))
                    || Boolean.TRUE.equals(state.get("cancel_requested"))) reject();
            completedStages(build, evidence);
            timeRemaining(budget);
            jdbc.update("""
                    UPDATE execution_builds SET status='SUCCEEDED',jar_bytes=?,jar_sha256=?,manifest_sha256=?,
                        worker_slot=null,queue_slot=null,finished_at=clock_timestamp()
                    WHERE id=?
                    """, digest.jarBytes(), digest.jarSha256(), digest.manifestSha256(), build.id());
            var result = builds.get(build.projectId(), build.id());
            timeRemaining(budget);
            return result;
        });
    }

    /** Failure cleanup still works after permission revocation; only the original binding is accepted. */
    ExecutionBuild fail(ExecutionBuild build, String code) throws IOException {
        outsideTransaction();
        if (build == null || code == null || !(code.equals("EXECUTION_ARTIFACT_INVALID")
                || code.equals(ExecutionFrontend.failureCode(new IOException(code))))) throw unavailable();
        return transact(() -> {
            jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, build.projectId());
            var state = locked(build);
            if (state.get("finished_at") != null) reject();
            // Unknown outcome is not proof of failure. Preserve pending, project exclusion and capacity.
            if (Boolean.TRUE.equals(state.get("execution_pending")) || "NEEDS_RECONCILIATION".equals(state.get("status"))
                    || code.equals("EXECUTION_PROCESS_UNSETTLED")) {
                jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION',error_code='EXECUTION_PROCESS_UNSETTLED' WHERE id=?", build.id());
            } else {
                String failure = Boolean.TRUE.equals(state.get("cancel_requested")) ? "EXECUTION_CANCELLED" : code;
                String terminal = failure.equals("EXECUTION_CANCELLED") ? "CANCELLED" : failure.equals("EXECUTION_TIMEOUT") ? "TIMED_OUT" : "FAILED";
                jdbc.update("""
                        UPDATE execution_builds SET status=?,error_code=?,worker_slot=null,queue_slot=null,finished_at=clock_timestamp()
                        WHERE id=?
                        """, terminal, failure, build.id());
            }
            return builds.get(build.projectId(), build.id());
        });
    }

    private Map<String, Object> locked(ExecutionBuild build) {
        var row = jdbc.queryForMap("SELECT * FROM execution_builds WHERE project_id=? AND id=? FOR UPDATE", build.projectId(), build.id());
        if (!Objects.equals(build.ownerId(), row.get("owner_id")) || !Objects.equals(build.sourceVersionId(), row.get("source_version_id"))
                || !Objects.equals(build.treeSha256(), row.get("tree_sha256")) || !Objects.equals(build.keySha256(), row.get("idempotency_key_sha256"))
                || !Objects.equals(build.requestSha256(), row.get("request_sha256"))) reject();
        return row;
    }

    private void completedStages(ExecutionBuild build, ExecutionArtifact.Evidence evidence) {
        var events = jdbc.queryForList("""
                SELECT payload::text FROM execution_build_events WHERE build_id=? AND payload->>'operation' IS NOT NULL
                ORDER BY seq LIMIT 13
                """, String.class, build.id());
        if (events.size() != ORDER.size() * 2) reject();
        var executions = new HashSet<UUID>();
        try {
            for (int index = 0; index < ORDER.size(); index++) {
                JsonNode start = JSON.readTree(events.get(index * 2)), end = JSON.readTree(events.get(index * 2 + 1));
                String operation = ORDER.get(index).name();
                if (!operation.equals(start.path("operation").asText()) || !operation.equals(end.path("operation").asText())
                        || !"started".equals(start.path("phase").asText()) || !"completed".equals(end.path("phase").asText())
                        || !start.has("code") || !start.get("code").isNull() || !end.has("code") || !end.get("code").isNull()
                        || !start.path("executionId").equals(end.path("executionId"))
                        || !executions.add(UUID.fromString(start.path("executionId").asText()))) reject();
                number(end, "stdoutBytes", 0, ExecutionProcess.STREAM_MAX_BYTES);
                number(end, "stderrBytes", 0, ExecutionProcess.STREAM_MAX_BYTES);
                if (ORDER.get(index) == UNIT_TEST) {
                    equal(end, "files", evidence.frontend().files()); equal(end, "suites", evidence.frontend().suites());
                    equal(end, "tests", evidence.frontend().tests());
                } else if (ORDER.get(index) == MAVEN_PACKAGE) {
                    equal(end, "files", evidence.javaTests().files()); equal(end, "tests", evidence.javaTests().tests());
                    if (end.has("suites")) reject();
                } else if (end.has("files") || end.has("tests") || end.has("suites")) reject();
            }
        } catch (IOException error) { reject(); }
    }
    private static void equal(JsonNode node, String key, long expected) { number(node, key, expected, expected); }
    private static void number(JsonNode node, String key, long min, long max) {
        var value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max) reject();
    }
    private static void timeRemaining(OwnedProcess.Budget budget) {
        try {
            if (Thread.currentThread().isInterrupted()) throw new IOException("EXECUTION_CANCELLED");
            var remaining = budget.remaining();
            if (remaining == null || remaining.isZero() || remaining.isNegative()) throw new IOException("EXECUTION_TIMEOUT");
        } catch (IOException error) {
            throw new UncheckedIOException(new IOException("EXECUTION_CANCELLED".equals(error.getMessage())
                    ? "EXECUTION_CANCELLED" : "EXECUTION_TIMEOUT"));
        }
    }
    private ExecutionBuild transact(Supplier<ExecutionBuild> action) throws IOException {
        outsideTransaction();
        try {
            return transaction.execute(tx -> { jdbc.execute("SET LOCAL lock_timeout = '3s'"); return action.get(); });
        } catch (UncheckedIOException error) { throw error.getCause(); }
        catch (RuntimeException error) { throw unavailable(); }
    }
    private static void outsideTransaction() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }
    private static void reject() { throw new IllegalStateException(); }
    private static IOException unavailable() { return new IOException("EXECUTION_STATE_UNAVAILABLE"); }
}
