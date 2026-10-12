package dev.atoms.demo.workspace;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** One local worker and one DB coordinator; a lost ownership check permanently stops this scheduler. */
@Component
@ConditionalOnProperty(name="atoms.build.enabled",havingValue="true")
public final class BuildScheduler implements SmartLifecycle {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BuildScheduler.class);
    private final BuildExecutionEnvironment execution;
    private final BuildLifecycle lifecycle;
    private final BuildWorker worker;
    private final LeaseFactory leases;
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"engineering-build-worker");t.setDaemon(true);return t;});
    private volatile boolean running;
    private BuildCoordinatorLease lease;
    @FunctionalInterface interface LeaseFactory { BuildCoordinatorLease acquire() throws Exception; }
    @Autowired public BuildScheduler(BuildExecutionEnvironment execution,BuildLifecycle lifecycle,BuildWorker worker,DataSource dataSource) {
        this(execution,lifecycle,worker,()->new BuildCoordinatorLease(dataSource));
    }
    BuildScheduler(BuildExecutionEnvironment execution,BuildLifecycle lifecycle,BuildWorker worker,LeaseFactory leases) {
        this.execution=execution;this.lifecycle=lifecycle;this.worker=worker;this.leases=leases;
    }
    @Override public synchronized void start() {
        if(running || stopped.get()) return;
        try {
            execution.settings(); lease=leases.acquire();
            lifecycle.interruptPrevious();
            running=true; execution.schedulerReady(true);
            executor.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS);
        } catch(Exception error) {
            stopped.set(true); execution.schedulerReady(false); closeLease(); executor.shutdown();
            LOG.warn("{\"event\":\"engineering_scheduler_unavailable\",\"entryPoint\":\"build_scheduler\",\"errorCode\":\"BUILD_ENVIRONMENT_NOT_READY\"}");
        }
    }
    private void tick() {
        if(stopped.get()) return;
        try {
            if(!lease.healthy()) throw BuildEnvironment.unavailable();
            recoverSettled();
            var job=lifecycle.claim();
            if(job!=null) worker.execute(job,()->stopped.get() || !lease.healthy());
        } catch(Exception error) {
            // A DB failure may have occurred after commit. Do not guess and dispatch again.
            stopped.set(true); execution.schedulerReady(false);
            LOG.warn("{\"event\":\"engineering_scheduler_interrupted\",\"entryPoint\":\"build_scheduler\",\"errorCode\":\"BUILD_INTERRUPTED\"}");
        }
    }
    private void recoverSettled() throws java.io.IOException {
        var paths=execution.settings().paths();
        for(var job:lifecycle.pending()) {
            try {
                var proof=new com.fasterxml.jackson.databind.ObjectMapper().readTree(BuildArtifacts.read(paths.checked(job.summary().id(),"private/settlement.json"),2048));
                if (!proof.isObject() || !proof.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of("type","executionId","settled","errorCode","loadedRuntimeId"))
                        || !proof.path("type").asText().equals("complete") || !proof.path("executionId").asText().equals(job.executionId().toString())
                        || !proof.path("settled").isBoolean() || !proof.path("settled").booleanValue()) continue;
                String code=proof.path("errorCode").isNull()?null:proof.path("errorCode").asText();
                boolean valid=code==null?proof.path("loadedRuntimeId").asText().matches("[a-f0-9]{64}"):
                        Set.of("BUILD_FAILED","BUILD_TIMEOUT","BUILD_CANCELLED","BUILD_INTERRUPTED","BUILD_INPUT_CHANGED").contains(code) && proof.path("loadedRuntimeId").isNull();
                if(valid) lifecycle.releaseSettled(job.summary().id(),job.executionId());
            } catch(java.io.IOException error) { /* Missing, partial or unsafe proof: retain the durable fence. */ }
        }
    }
    @Override public void stop() {
        stopped.set(true); execution.schedulerReady(false); executor.shutdown();
        try {
            if(!executor.awaitTermination(25,TimeUnit.SECONDS)) { executor.shutdownNow(); executor.awaitTermination(5,TimeUnit.SECONDS); }
        } catch(InterruptedException error) { executor.shutdownNow();Thread.currentThread().interrupt(); }
        finally { running=false;closeLease(); }
    }
    private void closeLease() { if(lease!=null) try { lease.close(); } catch(java.sql.SQLException error) { /* DB session loss keeps task fences. */ } }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
