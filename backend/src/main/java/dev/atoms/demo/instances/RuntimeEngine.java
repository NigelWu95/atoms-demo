package dev.atoms.demo.instances;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.workspace.BuildRootLease;
import dev.atoms.demo.workspace.RunnableBuildService;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One local coordinator; only live Process handles are controllable, never stored PIDs. */
@Component
public class RuntimeEngine {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RuntimeEngine.class);
    private final RuntimeEnvironment environment;
    private final InstanceRepository instances;
    private final RunnableBuildService builds;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final RuntimeHttp http=new RuntimeHttp();
    private final Map<UUID,RuntimeProcess> owned=new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"app-instance-scheduler");t.setDaemon(true);return t;});
    private RuntimeEnvironment.Settings settings;
    private BuildRootLease rootLease;
    private Connection coordinator;
    private int backendPid;
    private volatile boolean closed;
    public RuntimeEngine(RuntimeEnvironment environment,InstanceRepository instances,RunnableBuildService builds,DataSource dataSource,PlatformTransactionManager transactions){
        this.environment=environment;this.instances=instances;this.builds=builds;this.dataSource=dataSource;
        transaction=new TransactionTemplate(transactions);transaction.setTimeout(5);
    }
    @EventListener(ApplicationReadyEvent.class) public void start(){
        if(!environment.enabled())return;
        try{
            settings=environment.settings();rootLease=new BuildRootLease(settings.paths());coordinator=dataSource.getConnection();
            try(var query=coordinator.createStatement()){
                query.setQueryTimeout(3);
                try(var row=query.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(1748392066),pg_catalog.pg_backend_pid()")){
                    if(!row.next() || !row.getBoolean(1))throw new IllegalStateException();backendPid=row.getInt(2);
                }
            }
            transaction.executeWithoutResult(status->{
                instances.lock();
                for(var row:instances.active())instances.finish(row.summary().id(),"INTERRUPTED","RUNTIME_INTERRUPTED",!row.pending());
            });
            environment.ready=true;
            scheduler.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS);
        }catch(Exception error){environment.ready=false;release();LOG.warn("runtime_environment_unavailable: no new instances accepted");}
    }
    RuntimeProcess require(AppInstance instance){
        var process=owned.get(instance.id());
        if(closed || !environment.ready || !"RUNNING".equals(instance.status()) || process==null || !process.process.isAlive() || process.stopping!=0)
            throw RuntimeHttp.unavailable();
        return process;
    }
    private void tick(){
        if(closed)return;
        try{
            try(var query=coordinator.createStatement()){
                query.setQueryTimeout(2);try(var row=query.executeQuery("SELECT pg_catalog.pg_backend_pid()")){if(!row.next() || row.getInt(1)!=backendPid)throw new IllegalStateException();}
            }
            for(var row:instances.active()){
                UUID id=row.summary().id();var process=owned.get(id);
                if(row.summary().status().equals("INTERRUPTED") && process==null){
                    if(RuntimeProcess.settled(settings.paths().task(id),id))instances.finish(id,"INTERRUPTED","RUNTIME_INTERRUPTED",true);
                    continue;
                }
                if(row.summary().status().equals("QUEUED") && process==null && !closed){launch(row);continue;}
                if(process==null)continue;
                if(row.summary().status().equals("STOPPING"))process.stop(null);
                if(!process.process.isAlive()){
                    boolean settled=RuntimeProcess.settled(process.directory,id);
                    String error=process.stopError!=null?process.stopError:process.stopping==0?"RUNTIME_PROCESS_EXITED":null;
                    instances.finish(id,settled?(error==null?"STOPPED":"FAILED"):"INTERRUPTED",settled?error:"RUNTIME_INTERRUPTED",settled);
                    owned.remove(id);LOG.info("runtime_finished instanceId={} settled={} errorCode={}",id,settled,error);continue;
                }
                if(process.stopping!=0){
                    if(System.nanoTime()-process.stopping>Duration.ofSeconds(15).toNanos()){
                        process.process.getOutputStream().close();process.process.destroyForcibly();
                        instances.finish(id,"INTERRUPTED","RUNTIME_INTERRUPTED",false);owned.remove(id);
                    }
                    continue;
                }
                if(row.summary().status().equals("STARTING")){
                    if(healthy(process)){
                        if(instances.markReady(id))LOG.info("runtime_ready instanceId={} buildId={}",id,row.summary().buildId());
                    } else if(System.nanoTime()-process.created>Duration.ofSeconds(90).toNanos())process.stop("RUNTIME_START_TIMEOUT");
                }
            }
        }catch(Exception error){
            environment.ready=false;closed=true;
            for(var process:owned.values())process.stop("RUNTIME_INTERRUPTED");
            Throwable cause=error;while(cause.getCause()!=null && cause.getCause()!=cause)cause=cause.getCause();
            LOG.warn("runtime_coordinator_lost errorType={} causeType={} sqlState={}: owned instances stopping, admission disabled",
                    error.getClass().getSimpleName(),cause.getClass().getSimpleName(),cause instanceof java.sql.SQLException sql?sql.getSQLState():"none");
        }
    }
    private void launch(InstanceRepository.Stored row){
        UUID id=row.summary().id();
        boolean claimed=Boolean.TRUE.equals(transaction.execute(status->{instances.lock();return instances.claim(id);}));
        if(!claimed)return;
        try{
            rootLease.checkCapacity();
            var slot=settings.slots().stream().filter(value->value.id().equals(row.installation())).findFirst().orElseThrow(RuntimeEnvironment::unavailable);
            byte[] jar=builds.jar(row.owner(),row.summary().projectId().toString(),row.summary().buildId().toString());
            if(closed){instances.finish(id,"INTERRUPTED","RUNTIME_INTERRUPTED",true);return;}
            var process=RuntimeProcess.start(settings,slot,id,jar);owned.put(id,process);
            if(closed)process.stop("RUNTIME_INTERRUPTED");
        }catch(Exception error){
            instances.finish(id,"FAILED",error instanceof ApiException api?api.code():"RUNTIME_START_FAILED",true);
            LOG.warn("runtime_start_failed instanceId={}",id);
        }
    }
    private boolean healthy(RuntimeProcess process){
        try{
            var response=http.send(process.slot.port(),process.id,process.slot.id(),"/api/health","GET",null,Map.of());
            return response.status()==200 && new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.bytes()).path("status").asText().equals("UP");
        }catch(Exception error){return false;}
    }
    @PreDestroy public void close(){
        closed=true;environment.ready=false;scheduler.shutdown();
        for(var process:owned.values())process.stop("RUNTIME_INTERRUPTED");
        try{
            scheduler.awaitTermination(6,TimeUnit.SECONDS);
            long until=System.nanoTime()+Duration.ofSeconds(12).toNanos();
            while(owned.values().stream().anyMatch(p->p.process.isAlive()) && System.nanoTime()<until)Thread.sleep(100);
            for(var process:owned.values()){
                boolean settled=!process.process.isAlive() && RuntimeProcess.settled(process.directory,process.id);
                instances.finish(process.id,"INTERRUPTED","RUNTIME_INTERRUPTED",settled);
                try{process.process.getOutputStream().close();}catch(Exception ignored){}
            }
        }catch(Exception error){LOG.warn("runtime_shutdown_unresolved: restart retains execution fences");}
        finally{release();}
    }
    private void release(){
        if(coordinator!=null){
            try(var query=coordinator.createStatement()){query.setQueryTimeout(2);query.execute("SELECT pg_catalog.pg_advisory_unlock(1748392066)");}catch(Exception ignored){}
            try{coordinator.close();}catch(Exception ignored){}coordinator=null;
        }
        if(rootLease!=null){try{rootLease.close();}catch(Exception ignored){}rootLease=null;}
    }
}
