package dev.atoms.demo.workspace;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.engineering.EngineeringBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")
class BuildArchiveIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired BuildRepository builds;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean BuildExecutionEnvironment execution;
    @TempDir Path temporary;
    private UUID id;
    @AfterEach void settleOnlyFixture() { if(id!=null) jdbc.update("UPDATE engineering_builds SET status='CANCELLED',error_code='BUILD_CANCELLED',finished_at=now() WHERE id=? AND status='QUEUED'",id); }
    @Test void downloadsExactFrozenBytesWithoutTemplateReassemblyAndEnforcesOwnership() throws Exception {
        var browser=new ApiBrowser(port); var f=fixture(browser,true);
        byte[] expected=Files.readAllBytes(f.archive);
        var first=browser.download(f.url);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(expected);
        assertThat(first.headers().firstValue("content-disposition")).contains("attachment; filename=\"atoms-build-"+id+"-engineering.zip\"");
        assertThat(first.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(browser.download(f.url).body()).isEqualTo(expected);
        var stranger=new ApiBrowser(port);stranger.initialize();
        assertThat(stranger.download(f.url).statusCode()).isEqualTo(404);
        assertThat(new ApiBrowser(port).download(f.url).statusCode()).isEqualTo(401);
        Files.writeString(f.archive,"PRIVATE_ARCHIVE_CANARY");
        var damaged=browser.download(f.url);
        assertThat(damaged.statusCode()).isEqualTo(409);
        assertThat(new String(damaged.body())).contains("BUILD_ARTIFACT_UNAVAILABLE").doesNotContain("PRIVATE_ARCHIVE_CANARY",temporary.toString());
    }
    @Test void snapshotNotReadyIs409AndNeverFallsBackToCurrentVersion() throws Exception {
        var browser=new ApiBrowser(port);var f=fixture(browser,false);
        assertThat(browser.download(f.url).statusCode()).isEqualTo(409);
        verify(execution,never()).settings();
    }
    private Fixture fixture(ApiBrowser browser,boolean snapshot) throws Exception {
        UUID owner=UUID.fromString(browser.initialize().body().path("userId").asText()),project=UUID.randomUUID(),version=UUID.randomUUID();
        id=new TransactionTemplate(transactions).execute(tx->{
            jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'build archive fixture')",project,owner);
            jdbc.update("INSERT INTO versions(id,project_id,version_no,source_json,content_hash) VALUES (?,?,1,'{}',?)",version,project,"a".repeat(64));
            return builds.insert(owner,project,version,"a".repeat(64),"b".repeat(64)).id();
        });
        Path archive=null;
        if(snapshot) {
            var paths=new WorkspacePaths(temporary.toRealPath());
            when(execution.settings()).thenReturn(new BuildEnvironment.Settings(paths,temporary,temporary,temporary,java.util.Map.of()));
            var bundle=new EngineeringBundle("c".repeat(64),List.of(new EngineeringBundle.File("README.md","FROZEN_SOURCE".getBytes())));
            archive=new WorkspaceStore(paths).prepare(id,bundle).archive();
            jdbc.update("UPDATE engineering_builds SET bundle_id=?,archive_sha256=? WHERE id=?",bundle.bundleId(),BuildArtifacts.hash(Files.readAllBytes(archive)),id);
        }
        return new Fixture("/api/projects/"+project+"/builds/"+id+"/engineering.zip",archive);
    }
    private record Fixture(String url,Path archive) {}
}
