package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.project.ProjectService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.atoms.demo.projectexecution.ExecutionToolchain.Operation.*;

/** Internal journal for an already-claimed build. No scheduling, process launch or build-success transition. */
final class ExecutionBuildJournal implements ExecutionFrontend.Journal {
    private static final List<ExecutionToolchain.Operation> ORDER = List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionBuild build;
    private final Path directory;

    ExecutionBuildJournal(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
                          PlatformTransactionManager transactions, ExecutionBuild build,
                          ExecutionPaths paths, ExecutionPaths.Task task) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        this.settings = settings; this.projects = projects; this.jdbc = jdbc; this.build = build;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
        if (!paths.directory(task).getFileName().toString().equals(build.id().toString())) throw unavailable();
        directory = paths.project(task).resolve("frontend");
        paths.privateDirectoryKey(task, directory);
    }

    @Override public void begin(ExecutionToolchain.Operation operation, UUID execution) throws IOException {
        transact(() -> {
            if (execution == null || !settings.load().maintainerIds().contains(build.ownerId())
                    || projects.lock(build.ownerId(), build.projectId().toString()).archived()) reject();
            var row = locked();
            int index = ORDER.indexOf(operation);
            if (index < 0 || row.pending() || row.cancelled()) reject();
            String expected = index == 0 ? "PREPARING" : index == 1 ? "INSTALLING" : "CHECKING";
            if (!row.status().equals(expected)) reject();
            var previous = latest();
            if (index == 0 ? !previous.isEmpty() : previous.isEmpty()
                    || !"completed".equals(previous.get("phase"))
                    || !ORDER.get(index - 1).name().equals(previous.get("operation"))) reject();
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM execution_build_events WHERE build_id=? AND payload->>'executionId'=?)
                    """, Boolean.class, build.id(), execution.toString()))) reject();
            jdbc.update("UPDATE execution_builds SET status=?,execution_pending=true WHERE id=?",
                    operation == NPM_CI ? "INSTALLING" : "CHECKING", build.id());
            append(operation, execution, "started", null, null, null);
        });
    }

    @Override public void finish(ExecutionFrontend.Stage stage) throws IOException {
        transact(() -> {
            // Cleanup must remain possible after permission/config changes. Lock the same project, not a new owner.
            jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, build.projectId());
            var row = locked();
            var previous = latest();
            if (stage == null || stage.execution() == null || !ORDER.contains(stage.operation())
                    || !row.pending() || !Set.of("INSTALLING", "CHECKING").contains(row.status())
                    || !"started".equals(previous.get("phase"))
                    || !stage.execution().toString().equals(previous.get("executionId"))
                    || !stage.operation().name().equals(previous.get("operation"))) reject();
            if (stage.stopped() != null && !stage.stopped().matches(build.id(), stage.execution(), directory)) reject();
            String code = stage.code();
            if (code != null && !code.equals(ExecutionFrontend.failureCode(new IOException(code)))) reject();
            validateNumbers(stage);
            if (stage.stopped() == null) {
                code = "EXECUTION_PROCESS_UNSETTLED";
                jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION',error_code=? WHERE id=?", code, build.id());
                append(stage.operation(), stage.execution(), "unsettled", code, null, null);
                return;
            }
            if (row.cancelled()) code = "EXECUTION_CANCELLED";
            if (code == null && (stage.output() == null || stage.operation() == UNIT_TEST && stage.tests() == null)) reject();
            // Separate statements intentionally satisfy V023's rule: a pending row cannot become terminal.
            // Both statements plus the event commit atomically; no visible partially released intent.
            jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
            if (code != null) {
                String state = code.equals("EXECUTION_CANCELLED") ? "CANCELLED" : code.equals("EXECUTION_TIMEOUT") ? "TIMED_OUT" : "FAILED";
                jdbc.update("""
                        UPDATE execution_builds SET status=?,error_code=?,worker_slot=null,queue_slot=null,finished_at=now()
                        WHERE id=?
                        """, state, code, build.id());
            }
            append(stage.operation(), stage.execution(), code == null ? "completed" : "failed",
                    code, stage.output(), stage.tests());
        });
    }

    private record State(String status, boolean pending, boolean cancelled) {}
    private State locked() {
        var row = jdbc.queryForMap("SELECT * FROM execution_builds WHERE project_id=? AND id=? FOR UPDATE",
                build.projectId(), build.id());
        if (!build.ownerId().equals(row.get("owner_id")) || !build.sourceVersionId().equals(row.get("source_version_id"))
                || !build.treeSha256().equals(row.get("tree_sha256"))) reject();
        return new State((String) row.get("status"), (Boolean) row.get("execution_pending"), (Boolean) row.get("cancel_requested"));
    }
    private Map<String, Object> latest() {
        var rows = jdbc.queryForList("""
                SELECT payload->>'operation' AS operation,payload->>'executionId' AS "executionId",payload->>'phase' AS phase
                FROM execution_build_events WHERE build_id=? AND payload->>'operation' IS NOT NULL ORDER BY seq DESC LIMIT 1
                """, build.id());
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
    private void append(ExecutionToolchain.Operation operation, UUID execution, String phase, String code,
                        ExecutionProcess.Output output, ExecutionDiagnostics.Frontend tests) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("operation", operation.name()); fields.put("executionId", execution.toString()); fields.put("phase", phase);
        fields.put("code", code);
        if (output != null) { fields.put("stdoutBytes", output.stdoutBytes()); fields.put("stderrBytes", output.stderrBytes()); }
        if (tests != null) { fields.put("files", tests.files()); fields.put("suites", tests.suites()); fields.put("tests", tests.tests()); }
        final String payload;
        try { payload = JSON.writeValueAsString(fields); }
        catch (IOException error) { throw new IllegalStateException(); }
        int sequence = Objects.requireNonNull(jdbc.queryForObject("""
                UPDATE execution_builds SET last_event_seq=last_event_seq+1,event_bytes=event_bytes+octet_length(?::jsonb::text)
                WHERE id=? RETURNING last_event_seq
                """, Integer.class, payload, build.id()));
        jdbc.update("INSERT INTO execution_build_events(build_id,seq,payload) VALUES (?,?,?::jsonb)", build.id(), sequence, payload);
    }
    private static void validateNumbers(ExecutionFrontend.Stage stage) {
        if (stage.output() != null && (stage.output().stdoutBytes() < 0 || stage.output().stderrBytes() < 0
                || stage.output().stdoutBytes() > ExecutionProcess.STREAM_MAX_BYTES
                || stage.output().stderrBytes() > ExecutionProcess.STREAM_MAX_BYTES)) reject();
        var tests = stage.tests();
        if (tests != null && (stage.operation() != UNIT_TEST || tests.files() < 1 || tests.files() > 512
                || tests.suites() < tests.files() || tests.suites() > 10000 || tests.tests() < 1 || tests.tests() > 10000)) reject();
    }
    private void transact(Runnable action) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            transaction.executeWithoutResult(tx -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                action.run();
            });
        } catch (RuntimeException error) { throw unavailable(); }
    }
    private static void reject() { throw new IllegalStateException(); }
    private static IOException unavailable() { return new IOException("EXECUTION_STATE_UNAVAILABLE"); }
}
