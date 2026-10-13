package dev.atoms.demo.instances;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/instances")
public class InstanceController {
    private final InstanceService service;
    public InstanceController(InstanceService service){this.service=service;}
    @GetMapping Map<String,List<AppInstance>> list(Principal principal,@PathVariable String projectId){return Map.of("items",service.list(UUID.fromString(principal.getName()),projectId));}
    @GetMapping("/{id}") AppInstance get(Principal principal,@PathVariable String projectId,@PathVariable String id){return service.get(UUID.fromString(principal.getName()),projectId,id);}
    @PostMapping ResponseEntity<JsonNode> start(Principal principal,@PathVariable String projectId,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false)String key){
        var result=service.start(UUID.fromString(principal.getName()),projectId,body,key);return ResponseEntity.status(result.status()).body(result.body());
    }
    @PostMapping("/{id}/stops") ResponseEntity<JsonNode> stop(Principal principal,@PathVariable String projectId,@PathVariable String id,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false)String key){
        var result=service.stop(UUID.fromString(principal.getName()),projectId,id,body,key);return ResponseEntity.status(result.status()).body(result.body());
    }
}
