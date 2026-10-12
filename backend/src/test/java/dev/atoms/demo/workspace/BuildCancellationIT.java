package dev.atoms.demo.workspace;

import dev.atoms.demo.ApiBrowser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
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
class BuildCancellationIT {
    @LocalServerPort int port;
    @Autowired BuildRepository builds;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final List<UUID> ids=new ArrayList<>();
    @AfterEach void settleSimulatedExecutions() {
        for(UUID id:ids) jdbc.update("""
                UPDATE engineering_builds SET status=CASE WHEN status='BUILDING' THEN 'INTERRUPTED' ELSE 'CANCELLED' END,
                execution_pending=false,finished_at=now(),error_code='BUILD_CANCELLED' WHERE id=? AND status IN ('QUEUED','BUILDING','CANCELLING')
                """,id);
    }
    @Test void queuedCancellationIsIdempotentAndTerminalCancellationAddsNoEvent() throws Exception {
        var f=fixture(false); String key=UUID.randomUUID().toString();
        var first=f.browser.request("POST",f.path+"/cancellations","{}",key);
        assertThat(first.status()).isEqualTo(202);
        assertThat(f.browser.request("POST",f.path+"/cancellations","{}",key).body()).isEqualTo(first.body());
        var state=f.browser.get(f.path).body(); assertThat(state.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(state.path("lastEventSeq").asInt()).isEqualTo(2);
        assertThat(f.browser.request("POST",f.path+"/cancellations","{}",UUID.randomUUID().toString()).status()).isEqualTo(202);
        assertThat(f.browser.get(f.path).body()).isEqualTo(state);
    }
    @Test void runningCancellationRetainsTheExecutionFenceUntilWorkerConfirmsExit() throws Exception {
        var f=fixture(true);
        assertThat(f.browser.request("POST",f.path+"/cancellations","{}",UUID.randomUUID().toString()).status()).isEqualTo(202);
        var state=f.browser.get(f.path).body(); assertThat(state.path("status").asText()).isEqualTo("CANCELLING");
        assertThat(state.path("finishedAt").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT execution_pending FROM engineering_builds WHERE id=?",Boolean.class,f.id)).isTrue();
        var stranger=new ApiBrowser(port); stranger.initialize();
        assertThat(stranger.request("POST",f.path+"/cancellations","{}",UUID.randomUUID().toString()).status()).isEqualTo(404);
        assertThat(f.browser.request("POST",f.path+"/cancellations","{\"force\":true}",UUID.randomUUID().toString()).status()).isEqualTo(422);
    }
    @Test void concurrentCancellationIntentOnlyAddsOneStateTransition() throws Exception {
        var f=fixture(true); var pool=Executors.newFixedThreadPool(4);
        try {
            var requests=java.util.stream.IntStream.range(0,4).<java.util.concurrent.Callable<Integer>>mapToObj(i->()->f.browser.request("POST",f.path+"/cancellations","{}",UUID.randomUUID().toString()).status()).toList();
            for(var reply:pool.invokeAll(requests)) assertThat(reply.get()).isEqualTo(202);
            assertThat(f.browser.get(f.path).body().path("lastEventSeq").asInt()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
    @Test void cancellationRacingClaimOrCompletionKeepsOneLegalState() throws Exception {
        for(boolean completing:new boolean[]{false,true}) {
            var f=fixture(completing); var pool=Executors.newFixedThreadPool(2);
            var start=new java.util.concurrent.CountDownLatch(1);
            try {
                var cancel=pool.submit(()->{start.await();return f.browser.request("POST",f.path+"/cancellations","{}",UUID.randomUUID().toString()).status();});
                var transition=pool.submit(()->{
                    start.await();
                    new TransactionTemplate(transactions).executeWithoutResult(tx->{
                        builds.lockAdmission();
                        if(completing) jdbc.update("""
                                UPDATE engineering_builds SET status='SUCCEEDED',stage='COMPLETE',finished_at=now(),execution_pending=false,
                                  bundle_id=?,archive_sha256=?,loaded_runtime_id=?,jar_sha256=? WHERE id=? AND status='BUILDING'
                                ""","a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),f.id);
                        else jdbc.update("UPDATE engineering_builds SET status='BUILDING',stage='PREPARING',started_at=now(),execution_id=?,execution_pending=true WHERE id=? AND status='QUEUED'",UUID.randomUUID(),f.id);
                    }); return true;
                });
                start.countDown(); assertThat(cancel.get()).isEqualTo(202); assertThat(transition.get()).isTrue();
                var state=f.browser.get(f.path).body().path("status").asText();
                assertThat(state).isIn(completing?new String[]{"SUCCEEDED","CANCELLING"}:new String[]{"CANCELLED","CANCELLING"});
                assertThat(jdbc.queryForObject("SELECT execution_pending FROM engineering_builds WHERE id=?",Boolean.class,f.id)).isEqualTo(state.equals("CANCELLING"));
            } finally { pool.shutdownNow(); settleSimulatedExecutions(); }
        }
    }
    private Fixture fixture(boolean running) throws Exception {
        var browser=new ApiBrowser(port); UUID owner=UUID.fromString(browser.initialize().body().path("userId").asText());
        UUID project=UUID.randomUUID(),version=UUID.randomUUID();
        UUID id=new TransactionTemplate(transactions).execute(tx->{
            jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'取消构建')",project,owner);
            jdbc.update("INSERT INTO versions(id,project_id,version_no,source_json,content_hash) VALUES (?,?,1,'{}',?)",version,project,"a".repeat(64));
            var build=builds.insert(owner,project,version,"a".repeat(64),"b".repeat(64));
            if(running) jdbc.update("UPDATE engineering_builds SET status='BUILDING',stage='PREPARING',started_at=now(),execution_id=?,execution_pending=true WHERE id=?",UUID.randomUUID(),build.id());
            return build.id();
        }); ids.add(id);
        return new Fixture(browser,"/api/projects/"+project+"/builds/"+id,id);
    }
    private record Fixture(ApiBrowser browser,String path,UUID id) {}
}
