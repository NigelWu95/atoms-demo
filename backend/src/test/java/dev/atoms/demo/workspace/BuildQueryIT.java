package dev.atoms.demo.workspace;

import dev.atoms.demo.ApiBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")
class BuildQueryIT {
    @LocalServerPort int port;
    @Autowired BuildRepository builds;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Test void listsStablePagesAndTypedEventsWithoutPrivateFields() throws Exception {
        var browser=new ApiBrowser(port); UUID owner=UUID.fromString(browser.initialize().body().path("userId").asText());
        String project=browser.request("POST","/api/projects","{\"name\":\"构建历史\"}",UUID.randomUUID().toString()).body().path("id").asText();
        UUID first=seed(owner,project), second=seed(owner,project);
        String base="/api/projects/"+project+"/builds";
        var page=browser.get(base+"?limit=1"); assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("items")).hasSize(1);
        var next=browser.get(base+"?limit=1&cursor="+page.body().path("nextCursor").asText());
        assertThat(next.body().path("items")).hasSize(1);
        assertThat(next.body().path("nextCursor").isNull()).isTrue();
        assertThat(java.util.Set.of(page.body().path("items").get(0).path("id").asText(),next.body().path("items").get(0).path("id").asText()))
                .containsExactlyInAnyOrder(first.toString(),second.toString());
        var detail=browser.get(base+"/"+first);
        assertThat(detail.body().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(detail.body().properties()).hasSize(17);
        assertThat(detail.body().toString()).doesNotContain("ownerId","workspace","executionId","sourceHash","secret-canary");
        var events=browser.get(base+"/"+first+"/events?after=0&limit=1");
        assertThat(events.body().path("items").get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(events.body().path("nextCursor").asInt()).isEqualTo(1);
        var last=browser.get(base+"/"+first+"/events?after=1");
        assertThat(last.body().path("items").get(0).path("seq").asInt()).isEqualTo(2);
        assertThat(browser.get(base+"/"+first+"/events?after=2").body().path("items")).isEmpty();
        assertThat(browser.get(base+"?limit=101").status()).isEqualTo(422);
        assertThat(browser.get(base+"?cursor=invalid").status()).isEqualTo(400);
        assertThat(browser.get(base+"/"+first+"/events?after=3").status()).isEqualTo(400);
    }
    @Test void rejectsStrangersAndMixedProjectBuildIds() throws Exception {
        var browser=new ApiBrowser(port); UUID owner=UUID.fromString(browser.initialize().body().path("userId").asText());
        String project=browser.request("POST","/api/projects","{\"name\":\"私有构建\"}",UUID.randomUUID().toString()).body().path("id").asText();
        UUID build=seed(owner,project); String base="/api/projects/"+project+"/builds";
        var stranger=new ApiBrowser(port); stranger.initialize();
        for(String suffix:new String[]{"","/"+build,"/"+build+"/events"}) {
            assertThat(stranger.get(base+suffix).status()).isEqualTo(404);
            assertThat(new ApiBrowser(port).get(base+suffix).status()).isEqualTo(401);
        }
        String other=browser.request("POST","/api/projects","{\"name\":\"其他项目\"}",UUID.randomUUID().toString()).body().path("id").asText();
        assertThat(browser.get("/api/projects/"+other+"/builds/"+build).status()).isEqualTo(404);
        assertThat(browser.get(base+"/not-a-uuid").status()).isEqualTo(404);
    }
    private UUID seed(UUID owner,String project) {
        return new TransactionTemplate(transactions).execute(tx -> {
            UUID version=UUID.randomUUID();
            long count=jdbc.queryForObject("SELECT count(*) FROM versions WHERE project_id=?",Long.class,UUID.fromString(project));
            jdbc.update("INSERT INTO versions(id,project_id,version_no,source_json,content_hash) VALUES (?,?,?,'secret-canary',?)",version,UUID.fromString(project),count+1,"a".repeat(64));
            var build=builds.insert(owner,UUID.fromString(project),version,"a".repeat(64),"b".repeat(64));
            jdbc.update("UPDATE engineering_builds SET status='CANCELLED',finished_at=now(),error_code='BUILD_CANCELLED' WHERE id=?",build.id());
            builds.event(build.id(),"build.completed");
            return build.id();
        });
    }
}
