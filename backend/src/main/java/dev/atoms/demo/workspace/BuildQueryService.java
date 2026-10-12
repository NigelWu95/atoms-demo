package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BuildQueryService {
    private final ProjectService projects;
    private final JdbcTemplate jdbc;
    public BuildQueryService(ProjectService projects, JdbcTemplate jdbc) { this.projects=projects; this.jdbc=jdbc; }
    public record Page(List<EngineeringBuild> items,String nextCursor) {}
    public record Event(long seq,String type,String status,String stage,String errorCode,Instant createdAt) {}
    public record Events(List<Event> items,long nextCursor) {}
    public EngineeringBuild get(UUID owner,String projectId,String buildId) {
        var project=projects.get(owner,projectId);
        UUID id;
        try { id=UUID.fromString(buildId); if(!id.toString().equals(buildId)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException error) { throw missing(); }
        var rows=jdbc.query("SELECT * FROM engineering_builds WHERE owner_id=? AND project_id=? AND id=?",BuildRepository.ROW,owner,project.id(),id);
        if(rows.isEmpty()) throw missing();
        return rows.get(0).summary();
    }
    public Page list(UUID owner,String projectId,String cursor,int limit) {
        var project=projects.get(owner,projectId); limit(limit);
        List<BuildRepository.Stored> rows;
        if(cursor==null) rows=jdbc.query("SELECT * FROM engineering_builds WHERE owner_id=? AND project_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                BuildRepository.ROW,owner,project.id(),limit+1);
        else {
            var after=decode(cursor);
            rows=jdbc.query("SELECT * FROM engineering_builds WHERE owner_id=? AND project_id=? AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
                    BuildRepository.ROW,owner,project.id(),Timestamp.from(after.time()),after.id(),limit+1);
        }
        var items=rows.stream().limit(limit).map(BuildRepository.Stored::summary).toList();
        return new Page(items,rows.size()>limit?encode(items.get(items.size()-1)):null);
    }
    public Events events(UUID owner,String projectId,String buildId,long after,int limit) {
        var build=get(owner,projectId,buildId); limit(limit);
        if(after<0 || after>build.lastEventSeq()) throw invalidCursor();
        var items=jdbc.query("SELECT * FROM engineering_build_events WHERE build_id=? AND seq>? ORDER BY seq LIMIT ?",
                (rs,n)->new Event(rs.getLong("seq"),rs.getString("type"),rs.getString("status"),rs.getString("stage"),rs.getString("error_code"),rs.getTimestamp("created_at").toInstant()),build.id(),after,limit);
        return new Events(items,items.isEmpty()?after:items.get(items.size()-1).seq());
    }
    private static void limit(int limit) { if(limit<1||limit>100) throw new ApiException(422,"INVALID_INPUT","limit 必须在 1–100 之间"); }
    private static ApiException missing() { return new ApiException(404,"BUILD_NOT_FOUND","构建不存在或当前访客无权访问"); }
    private static ApiException invalidCursor() { return new ApiException(400,"INVALID_CURSOR","分页位置无效，请重新加载"); }
    private static String encode(EngineeringBuild build) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((build.createdAt()+"|"+build.id()).getBytes(StandardCharsets.UTF_8));
    }
    private static Cursor decode(String value) {
        try {
            if(value.length()>160) throw new IllegalArgumentException();
            String[] parts=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=2) throw new IllegalArgumentException();
            UUID id=UUID.fromString(parts[1]); if(!id.toString().equals(parts[1])) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]),id);
        } catch(IllegalArgumentException|java.time.DateTimeException error) { throw invalidCursor(); }
    }
    private record Cursor(Instant time,UUID id) {}
}
