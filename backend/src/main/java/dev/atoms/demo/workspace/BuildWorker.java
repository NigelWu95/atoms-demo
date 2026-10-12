package dev.atoms.demo.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.engineering.EngineeringSnapshotService;
import dev.atoms.demo.engineering.EngineeringZip;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** A single claimed task, executed outside transactions. Does not start application instances. */
@Component
public final class BuildWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BuildWorker.class);
    private final BuildExecutionEnvironment execution;
    private final EngineeringSnapshotService snapshots;
    private final BuildLifecycle lifecycle;
    private final Runner runner;
    private final Consumer<Path> verifyDatabase;
    private final ObjectMapper json = new ObjectMapper();
    @FunctionalInterface interface Runner {
        NativeBuildProcess.Result run(BuildEnvironment.Settings settings, Path task, UUID executionId, Path config,
                Map<String,String> env, BooleanSupplier stopped, Consumer<String> stages, Duration remaining) throws Exception;
    }
    @Autowired public BuildWorker(BuildExecutionEnvironment execution, EngineeringSnapshotService snapshots, BuildLifecycle lifecycle) {
        this(execution,snapshots,lifecycle,(settings,task,id,config,env,stop,stages,remaining) ->
                new NativeBuildProcess(remaining,Duration.ofSeconds(15)).run(settings.node(),task.resolve("private/build-runner.mjs"),
                        task,id,config,env,stop,stages), BuildTestDatabase::verify);
    }
    BuildWorker(BuildExecutionEnvironment execution, EngineeringSnapshotService snapshots, BuildLifecycle lifecycle, Runner runner, Consumer<Path> verifyDatabase) {
        this.execution=execution; this.snapshots=snapshots; this.lifecycle=lifecycle; this.runner=runner; this.verifyDatabase=verifyDatabase;
    }
    public void execute(BuildRepository.Stored job, BooleanSupplier shutdown) {
        UUID id=job.summary().id(), executionId=job.executionId();
        long started=System.nanoTime(), deadline=started+Duration.ofMinutes(10).toNanos();
        var reason=new AtomicReference<String>();
        NativeBuildProcess.Result result=new NativeBuildProcess.Result(true,"BUILD_FAILED",null);
        String jarHash=null;
        long[] nextCheck={0};
        BooleanSupplier stopped=()->{
            if (reason.get()!=null) return true;
            if (shutdown.getAsBoolean()) reason.compareAndSet(null,"BUILD_INTERRUPTED");
            else if (System.nanoTime()>=deadline) reason.compareAndSet(null,"BUILD_TIMEOUT");
            else if (System.nanoTime()>=nextCheck[0]) {
                nextCheck[0]=System.nanoTime()+Duration.ofSeconds(1).toNanos();
                try {
                    if (lifecycle.cancelled(id,executionId)) reason.compareAndSet(null,"BUILD_CANCELLED");
                    else execution.verifyUsage();
                } catch (IOException error) { reason.compareAndSet(null,"BUILD_CAPACITY_EXCEEDED"); }
                catch (RuntimeException error) { reason.compareAndSet(null,"BUILD_INTERRUPTED"); }
            }
            return reason.get()!=null;
        };
        try {
            requireRunning(stopped,reason);
            var settings=execution.settings();
            var paths=settings.paths();
            var source=snapshots.authorize(job.ownerId(),job.summary().projectId().toString(),job.summary().versionId().toString());
            if (!job.sourceHash().equals(source.contentHash())) throw failure("BUILD_INPUT_CHANGED");
            var bundle=snapshots.assemble(source,job.summary().templateDigest()).bundle();
            var store=new WorkspaceStore(paths);
            try { execution.verifyCapacity(); }
            catch (IOException error) { throw failure("BUILD_CAPACITY_EXCEEDED"); }
            var prepared=store.prepare(id,bundle);
            String archiveHash=BuildArtifacts.hash(BuildArtifacts.read(prepared.archive(),EngineeringZip.MAX_ZIP_BYTES));
            lifecycle.snapshot(id,executionId,bundle.bundleId(),archiveHash);
            Path task=paths.task(id);
            for (String resource : java.util.List.of("build-runner.mjs","supervisor.mjs","protocol.mjs")) {
                try (var input=BuildWorker.class.getResourceAsStream("/workspace/"+resource)) {
                    if (input==null) throw failure("BUILD_ENVIRONMENT_NOT_READY");
                    byte[] bytes=input.readNBytes(128*1024+1);
                    if (bytes.length>128*1024) throw failure("BUILD_ENVIRONMENT_NOT_READY");
                    WorkspaceStore.writeNew(paths.checked(id,"private/"+resource),bytes);
                }
            }
            Path config=paths.checked(id,"private/test.properties");
            WorkspaceStore.writeNew(config,BuildArtifacts.read(settings.testConfig(),16*1024));
            verifyDatabase.accept(config);
            store.verifySources(id,bundle);
            requireRunning(stopped,reason);
            var env=new HashMap<>(settings.environment()); env.put("TMPDIR",paths.checked(id,"private").toString());
            result=NativeBuildProcess.Result.unknown(); // A thrown native call can never prove there were no children.
            result=runner.run(settings,task,executionId,config,Map.copyOf(env),stopped,
                    stage->lifecycle.stage(id,executionId,stage),Duration.ofNanos(Math.max(1,deadline-System.nanoTime())));
            if (result.settled() && reason.get()!=null) result=new NativeBuildProcess.Result(true,reason.get(),null);
            if (result.settled() && result.errorCode()==null) {
                requireRunning(stopped,reason);
                try { store.verifySources(id,bundle); store.archive(id,archiveHash); }
                catch (IOException error) { throw failure("BUILD_INPUT_CHANGED"); }
                Path jar=paths.checked(id,"work/backend/target/app.jar");
                jarHash=BuildJar.verify(jar,result.loadedRuntimeId());
                Path saved=paths.checked(id,"result/app.jar");
                WorkspaceStore.writeNew(saved,BuildArtifacts.read(jar,BuildJar.MAX_BYTES));
                if (!jarHash.equals(BuildJar.verify(saved,result.loadedRuntimeId()))) throw failure("BUILD_ARTIFACT_UNAVAILABLE");
                WorkspaceStore.writeNew(paths.checked(id,"result/build.json"),json.writeValueAsBytes(Map.of(
                        "buildId",id,"projectId",job.summary().projectId(),"versionId",job.summary().versionId(),
                        "templateDigest",job.summary().templateDigest(),"buildPolicyVersion",job.summary().buildPolicyVersion(),
                        "bundleId",bundle.bundleId(),"archiveSha256",archiveHash,"loadedRuntimeId",result.loadedRuntimeId(),"jarSha256",jarHash)));
                try { execution.verifyUsage(); }
                catch (IOException error) { throw failure("BUILD_CAPACITY_EXCEEDED"); }
                requireRunning(stopped,reason);
            }
        } catch (Exception error) {
            String code=error instanceof ApiException api && java.util.Set.of("BUILD_INPUT_CHANGED","BUILD_ENVIRONMENT_NOT_READY","INVALID_ENGINEERING_BUNDLE",
                    "BUILD_INTERRUPTED","BUILD_CANCELLED","BUILD_TIMEOUT","BUILD_CAPACITY_EXCEEDED","BUILD_ARTIFACT_UNAVAILABLE").contains(api.code()) ? api.code() : "BUILD_FAILED";
            if (result.settled()) result=new NativeBuildProcess.Result(true,code,null);
            jarHash=null;
        }
        lifecycle.finish(id,executionId,result,jarHash);
        LOG.info("{}", "{\"event\":\"engineering_build_finished\",\"entryPoint\":\"build_scheduler\",\"buildId\":\""+id
                +"\",\"executionId\":\""+executionId+"\",\"settled\":"+result.settled()+",\"errorCode\":"
                +(result.errorCode()==null?"null":"\""+result.errorCode()+"\"")+",\"durationMs\":"+Duration.ofNanos(System.nanoTime()-started).toMillis()+"}");
    }
    private static void requireRunning(BooleanSupplier stopped, AtomicReference<String> reason) {
        if (stopped.getAsBoolean()) throw failure(reason.get());
    }
    private static ApiException failure(String code) { return new ApiException(503,code,EngineeringBuild.errorMessage(code)); }
}
