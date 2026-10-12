package dev.atoms.demo.workspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BuildRepository {
    private final JdbcTemplate jdbc;
    public BuildRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Stored(EngineeringBuild summary, UUID ownerId, String sourceHash, UUID executionId, boolean executionPending) {}
    static final RowMapper<Stored> ROW = (rs, n) -> new Stored(new EngineeringBuild(rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getObject("version_id", UUID.class), rs.getString("status"), rs.getString("stage"),
            rs.getString("template_digest"), rs.getString("build_policy_version"), rs.getString("bundle_id"), rs.getString("archive_sha256"),
            rs.getString("loaded_runtime_id"), rs.getString("jar_sha256"), rs.getString("error_code"), EngineeringBuild.errorMessage(rs.getString("error_code")),
            time(rs,"created_at"), time(rs,"started_at"), time(rs,"finished_at"), rs.getLong("last_event_seq")),
            rs.getObject("owner_id", UUID.class), rs.getString("source_content_hash"), rs.getObject("execution_id", UUID.class), rs.getBoolean("execution_pending"));

    @Transactional(propagation = Propagation.MANDATORY)
    public EngineeringBuild insert(UUID owner, UUID project, UUID version, String sourceHash, String templateDigest) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO engineering_builds(id,owner_id,project_id,version_id,source_content_hash,template_digest,workspace_key,status)
                VALUES (?,?,?,?,?,?,?,'QUEUED')
                """, id, owner, project, version, sourceHash, templateDigest, id);
        event(id, "build.queued");
        return find(id).summary();
    }
    Stored find(UUID id) {
        var rows = jdbc.query("SELECT * FROM engineering_builds WHERE id=?", ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void event(UUID id, String type) {
        int seq = jdbc.queryForObject("UPDATE engineering_builds SET last_event_seq=last_event_seq+1 WHERE id=? RETURNING last_event_seq", Integer.class, id);
        jdbc.update("""
                INSERT INTO engineering_build_events(build_id,seq,type,status,stage,error_code)
                SELECT id,?,?,status,stage,error_code FROM engineering_builds WHERE id=?
                """, seq, type, id);
    }
    private static Instant time(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
