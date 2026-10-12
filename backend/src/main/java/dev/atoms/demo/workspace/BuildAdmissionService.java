package dev.atoms.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import dev.atoms.demo.engineering.EngineeringSnapshotService;
import dev.atoms.demo.project.ProjectService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BuildAdmissionService {
    private final BuildRepository builds;
    private final ProjectService projects;
    private final EngineeringSnapshotService snapshots;
    private final BuildExecutionEnvironment execution;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public BuildAdmissionService(BuildRepository builds, ProjectService projects, EngineeringSnapshotService snapshots,
            BuildExecutionEnvironment execution, IdempotencyService idempotency, ObjectMapper json, PlatformTransactionManager transactions) {
        this.builds=builds; this.projects=projects; this.snapshots=snapshots; this.execution=execution; this.idempotency=idempotency; this.json=json;
        transaction=new TransactionTemplate(transactions); transaction.setTimeout(5);
    }
    public IdempotencyService.Result submit(UUID owner, String projectId, JsonNode body, String key) {
        UUID version = parse(body);
        var project = projects.get(owner, projectId);
        // No process/IO work in the short admission transaction. Defer rejection until AFTER replay.
        var preflight = preflight();
        return transaction.execute(status -> {
            builds.lockAdmission();
            projects.lock(owner, projectId);
            return idempotency.execute(owner,"POST","/api/projects/"+project.id()+"/builds",key,Map.of("versionId",version),() -> {
                if (preflight.failure()!=null) throw preflight.failure();
                if (builds.unresolved()) throw BuildEnvironment.unavailable();
                if (builds.activeProject(project.id())) throw new ApiException(409,"BUILD_ALREADY_ACTIVE","该项目已有构建任务，请等待或取消");
                if (builds.activeCount()>=4) throw new ApiException(503,"BUILD_QUEUE_FULL","构建队列已满，请稍后重试");
                var source = snapshots.authorize(owner,projectId,version.toString());
                var build = builds.insert(owner,project.id(),version,source.contentHash(),preflight.digest());
                return new IdempotencyService.Result(202,json.valueToTree(Map.of("buildId",build.id(),"projectId",project.id(),"versionId",version)));
            });
        });
    }
    private Preflight preflight() {
        var failure = execution.admissionFailure();
        if (failure != null) return new Preflight(null,failure);
        try { return new Preflight(snapshots.templateDigest(),null); }
        catch (RuntimeException error) { return new Preflight(null,BuildEnvironment.unavailable()); }
    }
    private UUID parse(JsonNode body) {
        try {
            if (!body.isObject() || body.size()!=1 || !body.path("versionId").isTextual()) throw new IllegalArgumentException();
            String value = body.path("versionId").asText(); UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException error) { throw new ApiException(422,"INVALID_INPUT","构建只接受已保存版本的 versionId"); }
    }
    private record Preflight(String digest, ApiException failure) {}
}
