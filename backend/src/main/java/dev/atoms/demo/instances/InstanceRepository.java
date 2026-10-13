package dev.atoms.demo.instances;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class InstanceRepository {
    private final JdbcTemplate jdbc;
    InstanceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Stored(AppInstance summary, UUID owner, UUID installation, boolean pending) {}
    private static final String SELECT="SELECT i.*,b.version_id,b.loaded_runtime_id FROM app_instances i JOIN engineering_builds b ON b.id=i.build_id ";
    private static final RowMapper<Stored> ROW=(rs,n)->new Stored(new AppInstance(rs.getObject("id",UUID.class),
            rs.getObject("project_id",UUID.class),rs.getObject("build_id",UUID.class),rs.getObject("version_id",UUID.class),
            rs.getString("status"),rs.getString("loaded_runtime_id"),rs.getString("error_code"),rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at")==null?null:rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toInstant()),
            rs.getObject("owner_id",UUID.class),rs.getObject("installation_id",UUID.class),rs.getBoolean("execution_pending"));
    List<Stored> recent(UUID owner,UUID project) {
        return jdbc.query(SELECT+"WHERE i.owner_id=? AND i.project_id=? ORDER BY i.created_at DESC,i.id DESC LIMIT 20",ROW,owner,project);
    }
    Stored find(UUID id) {
        var rows=jdbc.query(SELECT+"WHERE i.id=?",ROW,id); return rows.isEmpty()?null:rows.get(0);
    }
    List<Stored> active() { return jdbc.query(SELECT+"WHERE i.execution_pending OR i.status='QUEUED' ORDER BY i.created_at",ROW); }
    void lock() { jdbc.execute("SET LOCAL lock_timeout='3s'");jdbc.execute("SELECT pg_catalog.pg_advisory_xact_lock(1748392067)"); }
    int activeCount() { return jdbc.queryForObject("SELECT count(*) FROM app_instances WHERE execution_pending OR status='QUEUED'",Integer.class); }
    boolean projectActive(UUID project) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT FROM app_instances WHERE project_id=? AND (execution_pending OR status='QUEUED'))",Boolean.class,project);
    }
    UUID install(UUID project,List<RuntimeEnvironment.Slot> slots) {
        var existing=jdbc.query("SELECT id,config_fingerprint FROM runtime_installations WHERE project_id=?",(rs,n)->
                new Binding(rs.getObject(1,UUID.class),rs.getString(2)),project);
        if(!existing.isEmpty()) {
            var binding=existing.get(0);
            if(slots.stream().noneMatch(slot->slot.id().equals(binding.id()) && slot.fingerprint().equals(binding.fingerprint()))) throw RuntimeEnvironment.unavailable();
            return binding.id();
        }
        for(var slot:slots) {
            int changed=jdbc.update("INSERT INTO runtime_installations(id,project_id,config_fingerprint) VALUES (?,?,?) ON CONFLICT(id) DO NOTHING",slot.id(),project,slot.fingerprint());
            if(changed==1)return slot.id();
        }
        throw new dev.atoms.demo.common.ApiException(503,"RUNTIME_DATABASE_POOL_FULL","专用运行库已全部绑定项目，请由管理员补充；不会复用其他项目数据");
    }
    UUID insert(UUID owner,UUID project,UUID build,UUID installation) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO app_instances(id,owner_id,project_id,build_id,installation_id,status) VALUES (?,?,?,?,?,'QUEUED')",id,owner,project,build,installation);
        return id;
    }
    void finish(UUID id,String status,String error,boolean settled) {
        jdbc.update("UPDATE app_instances SET status=?,error_code=?,execution_pending=?,finished_at=now() WHERE id=?",status,error,!settled,id);
    }
    boolean claim(UUID id) {
        return jdbc.update("UPDATE app_instances SET status='STARTING',execution_pending=true,started_at=now() WHERE id=? AND status='QUEUED'",id)==1;
    }
    boolean markReady(UUID id) {
        return jdbc.update("UPDATE app_instances SET status='RUNNING' WHERE id=? AND status='STARTING'",id)==1;
    }
    void requestStop(UUID id) {
        jdbc.update("UPDATE app_instances SET status='STOPPING' WHERE id=? AND status IN ('STARTING','RUNNING')",id);
    }
    private record Binding(UUID id,String fingerprint) {}
}
