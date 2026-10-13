package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The only instance launcher input: authorized successful build, verified immutable bytes. */
@Service
public class RunnableBuildService {
    private final BuildQueryService queries;
    private final BuildExecutionEnvironment execution;
    public RunnableBuildService(BuildQueryService queries, BuildExecutionEnvironment execution) {
        this.queries=queries; this.execution=execution;
    }
    public byte[] jar(UUID owner, String project, String id) {
        var build=queries.get(owner,project,id);
        if (!"SUCCEEDED".equals(build.status())) throw new ApiException(409,"BUILD_NOT_READY","请先成功构建所选版本");
        try {
            var file=execution.settings().paths().checked(build.id(),"result/app.jar");
            if (!build.jarSha256().equals(BuildJar.verify(file,build.loadedRuntimeId()))) throw WorkspacePaths.rejected();
            byte[] bytes=BuildArtifacts.read(file,BuildJar.MAX_BYTES);
            if (!build.jarSha256().equals(BuildArtifacts.hash(bytes))) throw WorkspacePaths.rejected();
            return bytes;
        } catch (java.io.IOException error) {
            throw new ApiException(409,"BUILD_ARTIFACT_UNAVAILABLE","构建产物不可用或完整性校验失败，请重新构建");
        }
    }
}
