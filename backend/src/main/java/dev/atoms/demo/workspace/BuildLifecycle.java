package dev.atoms.demo.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions only. No filesystem, network probes or tool execution under the queue lock. */
@Service
@Transactional(timeout = 5)
public class BuildLifecycle {
    private static final List<String> STAGES = List.of("PREPARING", "INSTALLING", "CHECKING_FRONTEND", "BUILDING_FRONTEND", "CHECKING_BACKEND", "VERIFYING");
    private final BuildRepository builds;
    private final JdbcTemplate jdbc;
    public BuildLifecycle(BuildRepository builds, JdbcTemplate jdbc) { this.builds = builds; this.jdbc = jdbc; }

    public BuildRepository.Stored claim() {
        builds.lockAdmission();
        if (jdbc.queryForObject("SELECT EXISTS(SELECT FROM engineering_builds WHERE execution_pending)", Boolean.class)) return null;
        var rows = jdbc.query("SELECT * FROM engineering_builds WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1", BuildRepository.ROW);
        if (rows.isEmpty()) return null;
        UUID id = rows.get(0).summary().id(), execution = UUID.randomUUID();
        jdbc.update("UPDATE engineering_builds SET status='BUILDING',stage='PREPARING',started_at=now(),execution_id=?,execution_pending=true WHERE id=?", execution, id);
        builds.event(id, "build.stage"); return builds.find(id);
    }
    public void snapshot(UUID id, UUID execution, String bundle, String archive) {
        builds.lockAdmission();
        jdbc.update("UPDATE engineering_builds SET bundle_id=?,archive_sha256=? WHERE id=? AND execution_id=? AND status IN ('BUILDING','CANCELLING') AND bundle_id IS NULL", bundle, archive, id, execution);
    }
    public void stage(UUID id, UUID execution, String stage) {
        builds.lockAdmission();
        var current = builds.find(id);
        if (!owned(current, execution) || !current.summary().status().equals("BUILDING")) return;
        if (!STAGES.contains(stage) || STAGES.indexOf(stage) < STAGES.indexOf(current.summary().stage())) throw new IllegalArgumentException("Invalid stage");
        if (stage.equals(current.summary().stage())) return;
        jdbc.update("UPDATE engineering_builds SET stage=? WHERE id=?", stage, id); builds.event(id,"build.stage");
    }
    public void finish(UUID id, UUID execution, NativeBuildProcess.Result result, String jarHash) {
        builds.lockAdmission();
        var current = builds.find(id);
        if (!owned(current, execution) || !current.summary().active()) return;
        String state, error = result.errorCode(), runtime = null, jar = null;
        if (!result.settled()) { state = "INTERRUPTED"; error = "BUILD_INTERRUPTED"; }
        else if (current.summary().status().equals("CANCELLING")) { state = "CANCELLED"; error = "BUILD_CANCELLED"; }
        else if (error == null && jarHash != null) { state = "SUCCEEDED"; runtime = result.loadedRuntimeId(); jar = jarHash; }
        else if ("BUILD_INTERRUPTED".equals(error) || "BUILD_CANCELLED".equals(error)) { state = "INTERRUPTED"; error = "BUILD_INTERRUPTED"; }
        else { state = "FAILED"; if (error == null) error = "BUILD_FAILED"; }
        jdbc.update("""
                UPDATE engineering_builds SET status=?,stage=?,error_code=?,loaded_runtime_id=?,jar_sha256=?,finished_at=now(),execution_pending=?
                WHERE id=? AND execution_id=?
                """, state, state.equals("SUCCEEDED") ? "COMPLETE" : current.summary().stage(), error, runtime, jar, !result.settled(), id, execution);
        builds.event(id,"build.completed");
    }
    public void interruptPrevious() {
        builds.lockAdmission();
        var rows = jdbc.query("SELECT * FROM engineering_builds WHERE status IN ('QUEUED','BUILDING','CANCELLING')", BuildRepository.ROW);
        for (var build : rows) {
            jdbc.update("UPDATE engineering_builds SET status='INTERRUPTED',error_code='BUILD_INTERRUPTED',finished_at=now() WHERE id=?", build.summary().id());
            builds.event(build.summary().id(),"build.completed");
        }
    }
    public void releaseSettled(UUID id, UUID execution) {
        builds.lockAdmission();
        jdbc.update("UPDATE engineering_builds SET execution_pending=false WHERE id=? AND execution_id=? AND status='INTERRUPTED' AND execution_pending", id, execution);
    }
    @Transactional(readOnly = true) public List<BuildRepository.Stored> pending() {
        return jdbc.query("SELECT * FROM engineering_builds WHERE status='INTERRUPTED' AND execution_pending", BuildRepository.ROW);
    }
    @Transactional(readOnly = true) public boolean cancelled(UUID id, UUID execution) {
        var current = builds.find(id);
        return !owned(current, execution) || !current.summary().status().equals("BUILDING");
    }
    private boolean owned(BuildRepository.Stored build, UUID execution) {
        return build != null && execution.equals(build.executionId()) && build.executionPending();
    }
}
