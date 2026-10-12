package dev.atoms.demo.workspace;

import dev.atoms.demo.common.ApiException;
import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class BuildArchiveController {
    private final BuildQueryService queries;
    private final BuildExecutionEnvironment execution;
    private final Semaphore downloads=new Semaphore(2);
    public BuildArchiveController(BuildQueryService queries,BuildExecutionEnvironment execution) { this.queries=queries;this.execution=execution; }
    @GetMapping("/api/projects/{projectId}/builds/{buildId}/engineering.zip")
    ResponseEntity<byte[]> download(Principal principal,@PathVariable String projectId,@PathVariable String buildId) {
        var build=queries.get(UUID.fromString(principal.getName()),projectId,buildId);
        if(build.archiveSha256()==null) throw new ApiException(409,"BUILD_ARTIFACT_NOT_READY","该构建尚未保存工程快照");
        if(!downloads.tryAcquire()) throw new ApiException(503,"EXPORT_BUSY","工程下载繁忙，请稍后重试");
        try {
            byte[] bytes=new WorkspaceStore(execution.settings().paths()).archive(build.id(),build.archiveSha256());
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(bytes.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"atoms-build-"+build.id()+"-engineering.zip\"").body(bytes);
        } catch(java.io.IOException | ApiException error) {
            throw new ApiException(409,"BUILD_ARTIFACT_UNAVAILABLE","构建快照不可用或完整性校验失败，不会替换为其他版本");
        } finally { downloads.release(); }
    }
}
