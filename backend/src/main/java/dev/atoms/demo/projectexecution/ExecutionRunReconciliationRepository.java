package dev.atoms.demo.projectexecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transactional confirmation metadata only. Caller must own the ended worker and freshly verify its source/artifact/database. */
final class ExecutionRunReconciliationRepository {
    record View(ExecutionRun run, String historySha256) {}
    record Audit(UUID id, UUID runId, UUID projectId, UUID approvedBy, UUID executionId, String stoppedSha256,
                 String historySha256, String keySha256, String confirmationSha256, Instant createdAt) {}
    private static final RowMapper<Audit> ROW = (rs, n) -> new Audit(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getObject("approved_by", UUID.class), rs.getObject("execution_id", UUID.class),
            rs.getString("stopped_sha256"), rs.getString("history_sha256"), rs.getString("key_sha256"), rs.getString("confirmation_sha256"),
            rs.getTimestamp("created_at").toInstant());
    private final JdbcTemplate jdbc;
    private final ExecutionRunRepository runs;
    ExecutionRunReconciliationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; runs = new ExecutionRunRepository(jdbc); }

    ExecutionRun read(UUID owner, UUID project, UUID run) { return locked(owner, project, run); }

    View inspect(UUID owner, UUID project, UUID run, String history) {
        if (!hash(history)) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        var row = locked(owner, project, run);
        eligible(row);
        return new View(row, history);
    }

    Audit acknowledge(UUID owner, View expected, String currentHistory, String key) {
        valid(expected, key);
        if (!hash(currentHistory)) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        var original = expected.run();
        eligible(original);
        var current = locked(owner, original.projectId(), original.id());
        if (!owner.equals(original.ownerId()) || !currentHistory.equals(expected.historySha256())) conflict();
        String keyHash = digest(key), confirmation = fingerprint(expected);
        var prior = previous(owner, expected, current, keyHash, confirmation);
        if (prior != null) return prior;
        if (!current.equals(original)) conflict();
        eligible(current);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO execution_run_reconciliations(id,run_id,project_id,approved_by,execution_id,build_id,source_version_id,binding_id,
                    tree_sha256,jar_sha256,manifest_sha256,binding_identity_sha256,stopped_sha256,history_sha256,key_sha256,confirmation_sha256,previous_error)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, current.id(), current.projectId(), owner, current.executionId(), current.buildId(), current.sourceVersionId(),
                current.bindingId(), current.treeSha256(), current.jarSha256(), current.manifestSha256(), current.bindingIdentitySha256(),
                current.stoppedSha256(), currentHistory, keyHash, confirmation, current.errorCode());
        if (jdbc.update("UPDATE execution_runs SET status='FAILED',finished_at=clock_timestamp() WHERE id=? AND status='NEEDS_RECONCILIATION' AND NOT execution_pending",
                current.id()) != 1) conflict();
        return jdbc.queryForObject("SELECT * FROM execution_run_reconciliations WHERE id=?", ROW, id);
    }

    /** Read an already committed acceptance, without claiming to re-inspect today's business database. */
    Audit confirmed(UUID owner, View expected, String key) {
        valid(expected, key); eligible(expected.run());
        var current = locked(owner, expected.run().projectId(), expected.run().id());
        if (!owner.equals(expected.run().ownerId())) conflict();
        return previous(owner, expected, current, digest(key), fingerprint(expected));
    }
    private Audit previous(UUID owner, View expected, ExecutionRun current, String keyHash, String confirmation) {
        var original = expected.run();
        var prior = jdbc.query("SELECT * FROM execution_run_reconciliations WHERE project_id=? AND (run_id=? OR key_sha256=?)",
                ROW, original.projectId(), original.id(), keyHash);
        if (prior.isEmpty()) return null;
        if (prior.size() != 1) conflict();
        var audit = prior.get(0);
        if (!audit.runId().equals(original.id()) || !audit.approvedBy().equals(owner) || !audit.keySha256().equals(keyHash)
                || !audit.confirmationSha256().equals(confirmation) || !current.status().equals("FAILED")
                || current.finishedAt() == null || current.executionPending() || !audit.stoppedSha256().equals(current.stoppedSha256())) conflict();
        return audit;
    }
    private static void valid(View expected, String key) {
        if (expected == null || expected.run() == null || !hash(expected.historySha256()) || key == null || key.isBlank() || key.length() > 128
                || key.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff))
            throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
    }

    private ExecutionRun locked(UUID owner, UUID project, UUID run) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Reconciliation requires a transaction");
        if (owner == null || project == null || run == null) throw ExecutionRun.error(422, "RUN_INVALID_INPUT");
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        var projects = jdbc.queryForList("SELECT owner_id,archived FROM projects WHERE id=? FOR UPDATE", project);
        if (projects.isEmpty() || !owner.equals(projects.get(0).get("owner_id"))) throw ExecutionRun.error(404, "PROJECT_NOT_FOUND");
        if (Boolean.TRUE.equals(projects.get(0).get("archived"))) throw ExecutionRun.error(409, "RUN_PROJECT_ARCHIVED");
        if (jdbc.queryForList("SELECT id FROM execution_runs WHERE id=? AND project_id=? FOR UPDATE", run, project).isEmpty())
            throw ExecutionRun.error(404, "RUN_NOT_FOUND");
        var row = runs.get(project, run);
        if (!owner.equals(row.ownerId())) throw ExecutionRun.error(404, "RUN_NOT_FOUND");
        return row;
    }
    private static void eligible(ExecutionRun row) {
        if (!row.status().equals("NEEDS_RECONCILIATION") || row.executionPending() || row.executionId() == null
                || !hash(row.stoppedSha256()) || row.errorCode() == null || row.readyAt() != null || row.finishedAt() != null) conflict();
    }
    private static String fingerprint(View view) {
        var r = view.run();
        return digest("stopped-run-confirmation-v1\n" + r.id() + "\n" + r.projectId() + "\n" + r.ownerId() + "\n" + r.buildId()
                + "\n" + r.sourceVersionId() + "\n" + r.bindingId() + "\n" + r.migrationReviewId() + "\n" + r.treeSha256()
                + "\n" + r.jarSha256() + "\n" + r.manifestSha256() + "\n" + r.bindingIdentitySha256() + "\n" + r.keySha256()
                + "\n" + r.requestSha256() + "\n" + r.createdAt() + "\n" + r.executionId() + "\n" + r.stoppedSha256()
                + "\n" + r.errorCode() + "\n" + r.stopRequested() + "\n" + view.historySha256());
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void conflict() { throw ExecutionRun.error(409, "RUN_RECONCILIATION_CONFLICT"); }
}
