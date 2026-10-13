package dev.atoms.demo.instances;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.*;
import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.workspace.BuildQueryService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InstanceService {
    private final InstanceRepository instances;
    private final ProjectService projects;
    private final BuildQueryService builds;
    private final RuntimeEnvironment environment;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public InstanceService(InstanceRepository instances,ProjectService projects,BuildQueryService builds,RuntimeEnvironment environment,
            IdempotencyService idempotency,ObjectMapper json,PlatformTransactionManager transactions) {
        this.instances=instances;this.projects=projects;this.builds=builds;this.environment=environment;this.idempotency=idempotency;this.json=json;
        transaction=new TransactionTemplate(transactions);transaction.setTimeout(5);
    }
    public List<AppInstance> list(UUID owner,String project) {
        return instances.recent(owner,projects.get(owner,project).id()).stream().map(InstanceRepository.Stored::summary).toList();
    }
    InstanceRepository.Stored owned(UUID owner,String project,String id) {
        var p=projects.get(owner,project);UUID uuid;
        try {uuid=UUID.fromString(id);if(!uuid.toString().equals(id))throw new IllegalArgumentException();}
        catch(IllegalArgumentException error){throw missing();}
        var found=instances.find(uuid);
        if(found==null || !found.owner().equals(owner) || !found.summary().projectId().equals(p.id()))throw missing();
        return found;
    }
    public AppInstance get(UUID owner,String project,String id) {return owned(owner,project,id).summary();}
    public IdempotencyService.Result start(UUID owner,String project,JsonNode body,String key) {
        if(!body.isObject() || body.size()!=1 || !body.path("buildId").isTextual())throw invalid();
        var build=builds.get(owner,project,body.path("buildId").asText());
        RuntimeEnvironment.Settings settings=null;ApiException failure=null;
        try {settings=environment.settings();}catch(ApiException error){failure=error;}
        var candidate=settings;var rejection=failure;
        return transaction.execute(status->{
            instances.lock();projects.lock(owner,project);
            return idempotency.execute(owner,"POST","/api/projects/"+project+"/instances",key,Map.of("buildId",build.id()),()->{
                if(!"SUCCEEDED".equals(build.status()))throw new ApiException(409,"BUILD_NOT_READY","请先成功构建所选版本");
                if(rejection!=null)throw rejection;
                if(instances.projectActive(build.projectId()))throw new ApiException(409,"RUNTIME_ALREADY_ACTIVE","该项目已有运行实例，请先停止或确认退出");
                if(instances.activeCount()>=2)throw new ApiException(503,"RUNTIME_CAPACITY_EXCEEDED","最多同时运行两个实例，请先停止其他实例");
                UUID installation=instances.install(build.projectId(),candidate.slots());
                UUID id=instances.insert(owner,build.projectId(),build.id(),installation);
                return accepted(id);
            });
        });
    }
    public IdempotencyService.Result stop(UUID owner,String project,String id,JsonNode body,String key) {
        if(!body.isObject() || !body.isEmpty())throw invalid();
        var instance=owned(owner,project,id);
        return transaction.execute(status->{
            instances.lock();
            return idempotency.execute(owner,"POST","/api/projects/"+project+"/instances/"+id+"/stops",key,Map.of(),()->{
                var current=instances.find(instance.summary().id());
                if(current.summary().status().equals("QUEUED"))instances.finish(current.summary().id(),"STOPPED",null,true);
                else instances.jdbc.update("UPDATE app_instances SET status='STOPPING' WHERE id=? AND status IN ('STARTING','RUNNING')",current.summary().id());
                return accepted(current.summary().id());
            });
        });
    }
    private IdempotencyService.Result accepted(UUID id){return new IdempotencyService.Result(202,json.valueToTree(Map.of("instanceId",id)));}
    private static ApiException invalid(){return new ApiException(422,"INVALID_INPUT","实例操作参数无效");}
    static ApiException missing(){return new ApiException(404,"INSTANCE_NOT_FOUND","实例不存在或当前访客无权访问");}
}
