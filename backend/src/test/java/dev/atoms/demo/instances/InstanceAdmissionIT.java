package dev.atoms.demo.instances;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.artifact.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")
class InstanceAdmissionIT {
    @LocalServerPort int port;
    @Autowired ArtifactService artifacts;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired InstanceRepository repository;
    @MockitoBean RuntimeEnvironment environment;
    final List<UUID> owners=new ArrayList<>();
    @BeforeEach void ready(){
        var slots=java.util.stream.IntStream.range(0,8).mapToObj(i->new RuntimeEnvironment.Slot(UUID.randomUUID(),19000+i,"a".repeat(64),Path.of("/unused-fixture"))).toList();
        when(environment.admission()).thenReturn(new RuntimeEnvironment.Settings(null,null,slots));
    }
    @AfterEach void settle(){
        for(var owner:owners)jdbc.update("UPDATE app_instances SET status='STOPPED',execution_pending=false,finished_at=now() WHERE owner_id=?",owner);
    }
    @Test void concurrentIntentReplaysOneInstanceAndStopKeepsInstallation() throws Exception {
        var f=fixture();String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(4);
        String id;
        try{
            var requests=java.util.stream.IntStream.range(0,4).<java.util.concurrent.Callable<ApiBrowser.Reply>>mapToObj(i->()->f.browser.request("POST",f.path,f.body,key)).toList();
            var replies=pool.invokeAll(requests).stream().map(value->{try{return value.get();}catch(Exception error){throw new AssertionError(error);}}).toList();
            assertThat(replies).allMatch(value->value.status()==202);
            assertThat(replies.stream().map(value->value.body().path("instanceId").asText()).distinct()).hasSize(1);
            id=replies.get(0).body().path("instanceId").asText();
        }finally{pool.shutdownNow();}
        assertThat(f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString()).body().path("error").path("code").asText()).isEqualTo("RUNTIME_ALREADY_ACTIVE");
        when(environment.admission()).thenThrow(new dev.atoms.demo.common.ApiException(503,"RUNTIME_DISABLED","disabled"));
        assertThat(f.browser.request("POST",f.path,f.body,key).body().path("instanceId").asText()).isEqualTo(id);
        assertThat(repository.claim(UUID.fromString(id))).isTrue();assertThat(repository.markReady(UUID.fromString(id))).isTrue();
        assertThat(f.browser.request("POST",f.path+"/"+id+"/stops","{}",UUID.randomUUID().toString()).status()).isEqualTo(202);
        assertThat(f.browser.get(f.path+"/"+id).body().path("status").asText()).isEqualTo("STOPPING");
        UUID installation=jdbc.queryForObject("SELECT installation_id FROM app_instances WHERE id=?",UUID.class,UUID.fromString(id));
        repository.finish(UUID.fromString(id),"STOPPED",null,true);
        assertThat(jdbc.queryForObject("SELECT id FROM runtime_installations WHERE project_id=?",UUID.class,f.project)).isEqualTo(installation);
    }
    @Test void concurrentProjectsRespectTheGlobalTwoInstanceLimit() throws Exception {
        var fixtures=new ArrayList<Fixture>();for(int i=0;i<5;i++)fixtures.add(fixture());
        var pool=Executors.newFixedThreadPool(5);
        try{
            var requests=fixtures.stream().<java.util.concurrent.Callable<ApiBrowser.Reply>>map(f->()->f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString())).toList();
            var replies=pool.invokeAll(requests).stream().map(value->{try{return value.get();}catch(Exception error){throw new AssertionError(error);}}).toList();
            assertThat(replies.stream().filter(value->value.status()==202)).hasSize(2);
            assertThat(replies.stream().filter(value->value.status()!=202)).allMatch(value->value.status()==503 && value.body().path("error").path("code").asText().equals("RUNTIME_CAPACITY_EXCEEDED"));
        }finally{pool.shutdownNow();}
    }
    @Test void instanceAndAccessCapabilitiesAreOwnerOnlyAndStoppedDocumentIsNotFrameable() throws Exception {
        var f=fixture();String id=f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString()).body().path("instanceId").asText();
        var other=new ApiBrowser(port);other.initialize();
        for(String suffix:List.of("","/app","/state","/document"))assertThat(other.get(f.path+"/"+id+suffix).status()).isEqualTo(404);
        assertThat(other.request("POST",f.path+"/"+id+"/stops","{}",UUID.randomUUID().toString()).status()).isEqualTo(404);
        assertThat(f.browser.request("POST",f.path+"/"+id+"/stops","{}",UUID.randomUUID().toString(),"bad-csrf").status()).isEqualTo(403);
        f.browser.request("POST",f.path+"/"+id+"/stops","{}",UUID.randomUUID().toString());
        assertThat(f.browser.get(f.path+"/"+id).body().path("status").asText()).isEqualTo("STOPPED");
        assertThat(f.browser.get(f.path+"/"+id+"/document").status()).isEqualTo(503);
        assertThat(f.browser.get(f.path+"/"+id+"/document").raw().headers().firstValue("X-Frame-Options")).contains("DENY");
    }
    @Test void historicalBuildCannotBecomeAWritableInstance() throws Exception {
        var f=fixture();save(f.owner,f.project,1,f.version);
        var response=f.browser.request("POST",f.path,f.body,UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().path("error").path("code").asText()).isEqualTo("RUNTIME_VERSION_NOT_CURRENT");
    }
    private ArtifactService.Version save(UUID owner,UUID project,long revision,UUID base) {
        return artifacts.save(owner,project.toString(),revision,base,json.valueToTree(new Artifact(1,new Artifact.Manifest("实例准入测试","固定应用","web-static-v1",1,json.createObjectNode()),Map.of("view.html","<main>fixture</main>","styles.css","body{color:black}","main.js","// fixture"))));
    }
    private Fixture fixture() throws Exception {
        var browser=new ApiBrowser(port);UUID owner=UUID.fromString(browser.initialize().body().path("userId").asText());owners.add(owner);
        UUID project=UUID.fromString(browser.request("POST","/api/projects","{\"name\":\"实例准入测试\"}",UUID.randomUUID().toString()).body().path("id").asText());
        var version=save(owner,project,0,null);UUID build=UUID.randomUUID();String hash="a".repeat(64);
        jdbc.update("INSERT INTO engineering_builds(id,owner_id,project_id,version_id,source_content_hash,template_digest,workspace_key,status,stage,bundle_id,archive_sha256,loaded_runtime_id,jar_sha256,finished_at) VALUES (?,?,?,?,?,?,?,'SUCCEEDED','COMPLETE',?,?,?,?,now())",build,owner,project,version.id(),hash,hash,build,hash,hash,hash,hash);
        return new Fixture(browser,owner,project,version.id(),"/api/projects/"+project+"/instances","{\"buildId\":\""+build+"\"}");
    }
    private record Fixture(ApiBrowser browser,UUID owner,UUID project,UUID version,String path,String body){}
}
