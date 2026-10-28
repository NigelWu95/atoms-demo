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

/** Durable version-probe intents for an already-claimed build. Never launches or retries a process. */
final class ExecutionProbeJournal {
    private static final List<ExecutionToolchain.Kind> ORDER = List.of(ExecutionToolchain.Kind.NODE,
            ExecutionToolchain.Kind.NPM, ExecutionToolchain.Kind.JAVA, ExecutionToolchain.Kind.MAVEN);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionSettings settings;
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionBuild build;
    private final ExecutionPaths paths;
    private final ExecutionPaths.Task task;
    private final Path cwd;
    private final Object directoryKey;
    private final Map<ExecutionToolchain.Kind, ExecutionToolFiles.Summary> identities;

    ExecutionProbeJournal(ExecutionSettings settings, ProjectService projects, JdbcTemplate jdbc,
                          PlatformTransactionManager transactions, ExecutionBuild build, ExecutionPaths paths,
                          ExecutionPaths.Task task, ExecutionToolHome home, ExecutionToolchain tools) throws IOException {
        outsideTransaction();
        this.settings = settings; this.projects = projects; this.jdbc = jdbc; this.build = build;
        this.paths = paths; this.task = task;
        // Short transactions only; tool/source/home verification and launches belong outside the callback.
        // https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(5);
        if (!paths.directory(task).getFileName().toString().equals(build.id().toString())
                || !home.project().equals(paths.project(task))) throw unavailable();
        cwd = home.home(); directoryKey = paths.privateDirectoryKey(task, cwd);
        var captured = new EnumMap<ExecutionToolchain.Kind, ExecutionToolFiles.Summary>(ExecutionToolchain.Kind.class);
        for (var kind : ORDER) captured.put(kind, tools.identity(kind));
        identities = Map.copyOf(captured);
    }

    /** One-shot intent; successful return means committed, not permission to skip launch-time preflight. */
    void begin(ExecutionToolchain.Kind kind, UUID execution) throws IOException {
        outsideTransaction();
        if (!directoryKey.equals(paths.privateDirectoryKey(task, cwd))) throw unavailable();
        transact(() -> {
            if (projects.lock(build.ownerId(), build.projectId().toString()).archived()) reject();
            var row = locked();
            if (!settings.load().allows(build.ownerId()) || execution == null
                    || Boolean.TRUE.equals(row.get("execution_pending")) || Boolean.TRUE.equals(row.get("cancel_requested"))) reject();
            int index = ORDER.indexOf(kind);
            var history = history();
            if (index < 0 || history.size() != index * 2) reject();
            if (index > 0) {
                var last = history.get(history.size() - 1);
                if (!"completed".equals(last.get("phase")) || !ORDER.get(index - 1).name().equals(last.get("probe"))) reject();
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM execution_build_events WHERE build_id=?
                        AND (payload->>'operation' IS NOT NULL OR payload->>'executionId'=?))
                    """, Boolean.class, build.id(), execution.toString()))) reject();
            jdbc.update("UPDATE execution_builds SET execution_pending=true WHERE id=?", build.id());
            append(kind, execution, "started", null, null);
        });
    }

    /** Cleanup remains allowed after revocation. Unknown outcomes retain pending and worker capacity. */
    void finish(ExecutionToolchain.Kind kind, UUID execution, ExecutionToolProbe.Version version,
                OwnedProcess.Stopped stopped, String code) throws IOException {
        transact(() -> {
            jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, build.projectId());
            var row = locked(); var history = history();
            if (kind == null || execution == null || !Boolean.TRUE.equals(row.get("execution_pending")) || history.isEmpty()) reject();
            var last = history.get(history.size() - 1);
            if (!kind.name().equals(last.get("probe")) || !execution.toString().equals(last.get("executionId"))
                    || !"started".equals(last.get("phase"))) reject();
            if (stopped != null && !stopped.matches(build.id(), execution, cwd)) reject();
            if (code != null && !code.equals(ExecutionFrontend.failureCode(new IOException(code)))) reject();
            if (stopped == null || "EXECUTION_PROCESS_UNSETTLED".equals(code)) {
                jdbc.update("UPDATE execution_builds SET status='NEEDS_RECONCILIATION',error_code='EXECUTION_PROCESS_UNSETTLED' WHERE id=?", build.id());
                append(kind, execution, "unsettled", "EXECUTION_PROCESS_UNSETTLED", null);
                return;
            }
            String failure = Boolean.TRUE.equals(row.get("cancel_requested")) ? "EXECUTION_CANCELLED" : code;
            if (failure == null) validate(kind, version);
            jdbc.update("UPDATE execution_builds SET execution_pending=false WHERE id=?", build.id());
            if (failure != null) {
                String state = failure.equals("EXECUTION_CANCELLED") ? "CANCELLED" : failure.equals("EXECUTION_TIMEOUT") ? "TIMED_OUT" : "FAILED";
                jdbc.update("""
                        UPDATE execution_builds SET status=?,error_code=?,worker_slot=null,queue_slot=null,finished_at=clock_timestamp()
                        WHERE id=?
                        """, state, failure, build.id());
            }
            append(kind, execution, failure == null ? "completed" : "failed", failure, failure == null ? version : null);
        });
    }

    private void validate(ExecutionToolchain.Kind kind, ExecutionToolProbe.Version version) {
        if (version == null || version.tool() != kind || version.version() == null || version.version().length() > 32
                || !identities.get(kind).equals(version.identity())) reject();
        String prefix = switch (kind) { case NODE -> "v"; case NPM -> ""; case JAVA -> "openjdk "; case MAVEN -> "Apache Maven "; };
        try {
            if (!version.version().equals(ExecutionToolProbe.version(kind, prefix + version.version()))) reject();
        } catch (IOException error) { reject(); }
    }

    private Map<String, Object> locked() {
        var row = jdbc.queryForMap("SELECT * FROM execution_builds WHERE project_id=? AND id=? FOR UPDATE", build.projectId(), build.id());
        if (!build.ownerId().equals(row.get("owner_id")) || !build.sourceVersionId().equals(row.get("source_version_id"))
                || !build.treeSha256().equals(row.get("tree_sha256")) || !build.keySha256().equals(row.get("idempotency_key_sha256"))
                || !build.requestSha256().equals(row.get("request_sha256")) || !"PREPARING".equals(row.get("status"))) reject();
        return row;
    }
    private List<Map<String, Object>> history() {
        return jdbc.queryForList("""
                SELECT payload->>'probe' AS probe,payload->>'phase' AS phase,payload->>'executionId' AS "executionId"
                FROM execution_build_events WHERE build_id=? AND payload->>'probe' IS NOT NULL ORDER BY seq LIMIT 9
                """, build.id());
    }
    private void append(ExecutionToolchain.Kind kind, UUID execution, String phase, String code, ExecutionToolProbe.Version version) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("probe", kind.name()); fields.put("executionId", execution.toString()); fields.put("phase", phase); fields.put("code", code);
        if (version != null) {
            fields.put("version", version.version()); fields.put("sha256", version.identity().sha256());
            fields.put("files", version.identity().files()); fields.put("bytes", version.identity().bytes());
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
    private void transact(Runnable action) throws IOException {
        outsideTransaction();
        try { transaction.executeWithoutResult(tx -> { jdbc.execute("SET LOCAL lock_timeout = '3s'"); action.run(); }); }
        catch (RuntimeException error) { throw unavailable(); }
    }
    private static void outsideTransaction() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }
    private static void reject() { throw new IllegalStateException(); }
    private static IOException unavailable() { return new IOException("EXECUTION_STATE_UNAVAILABLE"); }
}
