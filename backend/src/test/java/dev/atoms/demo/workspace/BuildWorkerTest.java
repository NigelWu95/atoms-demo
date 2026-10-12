package dev.atoms.demo.workspace;

import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.engineering.EngineeringBundle;
import dev.atoms.demo.engineering.EngineeringSnapshotService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildWorkerTest {
    @TempDir Path temporary;
    final BuildLifecycle lifecycle = mock(BuildLifecycle.class);
    final BuildExecutionEnvironment execution = mock(BuildExecutionEnvironment.class);
    final EngineeringSnapshotService snapshots = mock(EngineeringSnapshotService.class);
    final String hash = "a".repeat(64);

    @Test void freezesAndVerifiesBeforePublishingSuccess() throws Exception {
        var job = fixture();
        var jarFixture = new BuildJarTest(); jarFixture.temporary = temporary;
        String runtime = BuildJarTest.identity(BuildJarTest.resources());
        Path jar = jarFixture.jar(BuildJarTest.resources(), runtime);
        var worker = new BuildWorker(execution, snapshots, lifecycle, (s, task, id, config, env, stop, stages, remaining) -> {
            assertThat(config).startsWith(task.resolve("private"));
            assertThat(env).doesNotContainKey("STANDALONE_TEST_CONFIG");
            Path target = task.resolve("work/backend/target");
            Files.createDirectories(target); Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
            Files.copy(jar, target.resolve("app.jar"));
            Files.setPosixFilePermissions(target.resolve("app.jar"), PosixFilePermissions.fromString("rw-------"));
            stages.accept("VERIFYING");
            return new NativeBuildProcess.Result(true, null, runtime);
        }, config -> {});
        worker.execute(job, () -> false);
        verify(lifecycle).finish(eq(job.summary().id()), eq(job.executionId()), eq(new NativeBuildProcess.Result(true,null,runtime)), matches("[a-f0-9]{64}"));
        Path task = execution.settings().paths().task(job.summary().id());
        assertThat(Files.readString(task.resolve("result/build.json"))).contains(runtime, job.summary().versionId().toString()).doesNotContain("TEST_SECRET");
        assertThat(Files.exists(task.resolve("snapshot/engineering.zip"))).isTrue();
    }
    @Test void neverStartsOnShutdownAndNeverReleasesAnUnknownExecution() throws Exception {
        var job = fixture();
        BuildWorker.Runner runner = mock(BuildWorker.Runner.class);
        var worker = new BuildWorker(execution,snapshots,lifecycle,runner,config -> {});
        worker.execute(job, () -> true);
        verifyNoInteractions(runner);
        verify(lifecycle).finish(eq(job.summary().id()),eq(job.executionId()),eq(new NativeBuildProcess.Result(true,"BUILD_INTERRUPTED",null)),isNull());
        reset(lifecycle);
        when(runner.run(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(NativeBuildProcess.Result.unknown());
        worker.execute(job, () -> false);
        verify(lifecycle).finish(eq(job.summary().id()),eq(job.executionId()),eq(NativeBuildProcess.Result.unknown()),isNull());
    }
    @Test void rejectsChangedSourcesEvenAfterAReportedSuccess() throws Exception {
        var job = fixture();
        var worker = new BuildWorker(execution,snapshots,lifecycle,(s,task,id,config,env,stop,stages,remaining)->{
            Files.writeString(task.resolve("work/README.md"),"tampered");
            return new NativeBuildProcess.Result(true,null,hash);
        },config -> {});
        worker.execute(job, () -> false);
        verify(lifecycle).finish(eq(job.summary().id()),eq(job.executionId()),eq(new NativeBuildProcess.Result(true,"BUILD_INPUT_CHANGED",null)),isNull());
    }
    @Test void refusesCapacityBeforeCreatingDirectoriesOrInvokingTools() throws Exception {
        var job=fixture(); BuildWorker.Runner runner=mock(BuildWorker.Runner.class);
        doThrow(new java.io.IOException("PRIVATE_DISK_CANARY")).when(execution).verifyCapacity();
        new BuildWorker(execution,snapshots,lifecycle,runner,config->{}).execute(job,()->false);
        verifyNoInteractions(runner);
        verify(lifecycle).finish(eq(job.summary().id()),eq(job.executionId()),eq(new NativeBuildProcess.Result(true,"BUILD_CAPACITY_EXCEEDED",null)),isNull());
        assertThat(Files.exists(execution.settings().paths().task(job.summary().id()))).isFalse();
    }
    private BuildRepository.Stored fixture() throws Exception {
        Path root = Files.createDirectory(temporary.toRealPath().resolve(UUID.randomUUID().toString()),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path config = temporary.toRealPath().resolve(UUID.randomUUID()+".properties"); WorkspaceStore.writeNew(config,"TEST_SECRET".getBytes());
        when(execution.settings()).thenReturn(new BuildEnvironment.Settings(new WorkspacePaths(root),Path.of("/usr/bin/node"),Path.of("/usr/bin/java"),config,Map.of("PATH","/usr/bin:/bin")));
        UUID id=UUID.randomUUID(),project=UUID.randomUUID(),version=UUID.randomUUID(),owner=UUID.randomUUID();
        var summary=new EngineeringBuild(id,project,version,"BUILDING","PREPARING",hash,"native-v1",null,null,null,null,null,null,Instant.now(),Instant.now(),null,2);
        var source=new ArtifactService.Version(version,project,1,hash,null,null,Instant.now(),null);
        when(snapshots.authorize(owner,project.toString(),version.toString())).thenReturn(source);
        when(snapshots.assemble(source,hash)).thenReturn(new EngineeringSnapshotService.Snapshot(hash,new EngineeringBundle(hash,List.of(
                new EngineeringBundle.File("README.md","source".getBytes()),new EngineeringBundle.File("backend/mvnw","fixed".getBytes())))));
        return new BuildRepository.Stored(summary,owner,hash,UUID.randomUUID(),true);
    }
}
