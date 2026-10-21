package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.execution.OwnedProcess;
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
final class ExecutionBuildJournal implements ExecutionFrontend.Journal, ExecutionJava.Journal {
    private static final List<ExecutionToolchain.Operation> ORDER = List.of(NPM_CI, TYPECHECK, LINT, UNIT_TEST, FRONTEND_BUILD, MAVEN_PACKAGE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionBuild build;
    private final Path frontend, backend;
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;

    ExecutionBuildJournal(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
                          PlatformTransactionManager transactions, ExecutionBuild build,
                          ExecutionPaths paths, ExecutionPaths.Task task) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        this.settings = settings; this.projects = projects; this.jdbc = jdbc; this.build = build;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
        if (!paths.directory(task).getFileName().toString().equals(build.id().toString())) throw unavailable();
        this.paths = paths; this.task = task;
        frontend = paths.project(task).resolve("frontend");
        backend = paths.project(task).resolve("backend");
        paths.privateDirectoryKey(task, frontend);
    }

    @Override public void begin(ExecutionToolchain.Operation operation, UUID execution) throws IOException {
        if (operation == MAVEN_PACKAGE) throw unavailable(); // Java has a separate typed entry point.
        beginStage(operation, execution);
    }

    @Override public void begin(UUID execution) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try { paths.privateDirectoryKey(task, backend); }
        catch (IOException | RuntimeException error) { throw unavailable(); }
        beginStage(MAVEN_PACKAGE, execution);
    }

    private void beginStage(ExecutionToolchain.Operation operation, UUID execution) throws IOException {
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
                    operation == NPM_CI ? "INSTALLING" : operation == MAVEN_PACKAGE ? "PACKAGING" : "CHECKING", build.id());
            append(operation, execution, "started", null, null, null);
        });
    }

    @Override public void finish(ExecutionFrontend.Stage stage) throws IOException {
        if (stage == null || stage.operation() == MAVEN_PACKAGE) throw unavailable();
        var tests = stage.tests();
        finishStage(new Completion(stage.operation(), stage.execution(), stage.code(), stage.stopped(), stage.output(),
                tests == null ? null : new Counts(tests.files(), tests.suites(), tests.tests())));
    }

    @Override public void finish(ExecutionJava.Stage stage) throws IOException {
        if (stage == null) throw unavailable();
        var tests = stage.tests();
        finishStage(new Completion(MAVEN_PACKAGE, stage.execution(), stage.code(), stage.stopped(), stage.output(),
                tests == null ? null : new Counts(tests.files(), null, tests.tests())));
    }

    private record Counts(int files, Integer suites, int tests) {}
    private record Completion(ExecutionToolchain.Operation operation, UUID execution, String code,
                              OwnedProcess.Stopped stopped, ExecutionProcess.Output output, Counts tests) {}

    private void finishStage(Completion stage) throws IOException {
        transact(() -> {
            // Cleanup must remain possible after permission/config changes. Lock the same project, not a new owner.
            jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, build.projectId());
            var row = locked();
            var previous = latest();
            if (stage == null || stage.execution() == null || !ORDER.contains(stage.operation())
                    || !row.pending() || (stage.operation() == MAVEN_PACKAGE ? !row.status().equals("PACKAGING")
                        : !Set.of("INSTALLING", "CHECKING").contains(row.status()))
                    || !"started".equals(previous.get("phase"))
                    || !stage.execution().toString().equals(previous.get("executionId"))
                    || !stage.operation().name().equals(previous.get("operation"))) reject();
            Path directory = stage.operation() == MAVEN_PACKAGE ? backend : frontend;
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
            if (code == null && (stage.output() == null
                    || (stage.operation() == UNIT_TEST || stage.operation() == MAVEN_PACKAGE) && stage.tests() == null)) reject();
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
                        ExecutionProcess.Output output, Counts tests) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("operation", operation.name()); fields.put("executionId", execution.toString()); fields.put("phase", phase);
        fields.put("code", code);
        if (output != null) { fields.put("stdoutBytes", output.stdoutBytes()); fields.put("stderrBytes", output.stderrBytes()); }
        if (tests != null) {
            fields.put("files", tests.files()); fields.put("tests", tests.tests());
            if (tests.suites() != null) fields.put("suites", tests.suites());
        }
        final String payload;
        try { payload = JSON.writeValueAsString(fields); }
        catch (IOException error) { throw new IllegalStateException(); }
        int sequence = Objects.requireNonNull(jdbc.queryForObject("""
                UPDATE execution_builds SET last_event_seq=last_event_seq+1,event_bytes=event_bytes+octet_length(?::jsonb::text)
                WHERE id=? RETURNING last_event_seq
                """, Integer.class, payload, build.id()));
        jdbc.update("INSERT INTO execution_build_events(build_id,seq,payload) VALUES (?,?,?::jsonb)", build.id(), sequence, payload);
    }
    private static void validateNumbers(Completion stage) {
        if (stage.output() != null && (stage.output().stdoutBytes() < 0 || stage.output().stderrBytes() < 0
                || stage.output().stdoutBytes() > ExecutionProcess.STREAM_MAX_BYTES
                || stage.output().stderrBytes() > ExecutionProcess.STREAM_MAX_BYTES)) reject();
        var tests = stage.tests();
        if (tests == null) return;
        if (tests.files() < 1 || tests.files() > 512 || tests.tests() < 1 || tests.tests() > 10000) reject();
        if (stage.operation() == MAVEN_PACKAGE) {
            if (tests.suites() != null || tests.tests() < tests.files()) reject();
        } else if (stage.operation() != UNIT_TEST || tests.suites() == null
                || tests.suites() < tests.files() || tests.suites() > 10000) reject();
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
