package dev.atoms.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.common.IdempotencyService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildCancellationService {
    private final BuildRepository builds;
    private final BuildQueryService queries;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    public BuildCancellationService(BuildRepository builds,BuildQueryService queries,IdempotencyService idempotency,ObjectMapper json) {
        this.builds=builds; this.queries=queries; this.idempotency=idempotency; this.json=json;
    }
    @Transactional(timeout=5)
    public IdempotencyService.Result cancel(UUID owner,String project,String id,JsonNode body,String key) {
        if(!body.isObject()||!body.isEmpty()) throw new ApiException(422,"INVALID_INPUT","取消构建只接受空对象");
        builds.lockAdmission();
        var build=queries.get(owner,project,id);
        return idempotency.execute(owner,"POST","/api/projects/"+build.projectId()+"/builds/"+build.id()+"/cancellations",key,Map.of(),()->{
            builds.requestCancellation(build);
            return new IdempotencyService.Result(202,json.valueToTree(Map.of("buildId",build.id())));
        });
    }
}
