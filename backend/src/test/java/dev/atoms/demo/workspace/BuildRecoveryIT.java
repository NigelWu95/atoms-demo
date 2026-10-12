package dev.atoms.demo.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class BuildRecoveryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired BuildRepository builds;
    @Autowired BuildLifecycle lifecycle;
    @TempDir Path temporary;
    @Test void onlyACompleteMatchingPrivateProofCanReleaseAnInterruptedFence() throws Exception {
        UUID owner=UUID.randomUUID(),project=UUID.randomUUID(),version=UUID.randomUUID();
        jdbc.update("INSERT INTO guest_users(id) VALUES (?)",owner);
        jdbc.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'recovery fixture')",project,owner);
        jdbc.update("INSERT INTO versions(id,project_id,version_no,source_json,content_hash) VALUES (?,?,1,'{}',?)",version,project,"a".repeat(64));
        var queued=builds.insert(owner,project,version,"a".repeat(64),"b".repeat(64));
        var job=lifecycle.claim(); assertThat(job.summary().id()).isEqualTo(queued.id());
        lifecycle.interruptPrevious();
        var paths=new WorkspacePaths(temporary.toRealPath());
        var execution=mock(BuildExecutionEnvironment.class);
        when(execution.settings()).thenReturn(new BuildEnvironment.Settings(paths,temporary,temporary,temporary,Map.of()));
        var scheduler=new BuildScheduler(execution,lifecycle,mock(BuildWorker.class),()->{throw new AssertionError();});
        scheduler.recoverSettled();
        assertThat(builds.find(queued.id()).executionPending()).isTrue();
        Path task=paths.create(queued.id());
        Files.createDirectory(task.resolve("private"),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(WorkspacePaths.DIRECTORY_MODE));
        Path report=paths.checked(queued.id(),"private/settlement.json");
        String valid="{\"type\":\"complete\",\"executionId\":\""+job.executionId()+"\",\"settled\":true,\"errorCode\":\"BUILD_INTERRUPTED\",\"loadedRuntimeId\":null}";
        WorkspaceStore.writeNew(report,new byte[0]);
        for(String invalid:java.util.List.of("{",valid.replace(job.executionId().toString(),UUID.randomUUID().toString()),
                valid.replace("true","false"),valid.replace("BUILD_INTERRUPTED","FORGED_CANARY"),valid.replace("null","\"bad\""))) {
            Files.writeString(report,invalid);scheduler.recoverSettled();
            assertThat(builds.find(queued.id()).executionPending()).isTrue();
        }
        Files.writeString(report,valid);
        Path alias=Files.createLink(task.resolve("private/proof-alias"),report);
        scheduler.recoverSettled();assertThat(builds.find(queued.id()).executionPending()).isTrue();
        Files.delete(alias);
        scheduler.recoverSettled();
        assertThat(builds.find(queued.id()).executionPending()).isFalse();
        assertThat(builds.find(queued.id()).summary().status()).isEqualTo("INTERRUPTED");
        assertThat(lifecycle.claim()).isNull();
        scheduler.stop();
    }
}
