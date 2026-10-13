package dev.atoms.demo.instances;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.preview.RuntimeDocumentPolicy;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/instances/{id}")
public class RuntimeAccessController {
    private final RuntimeAccessService access;
    public RuntimeAccessController(RuntimeAccessService access){this.access=access;}
    @GetMapping(value="/{resource:app|state}",produces="application/json")
    ResponseEntity<byte[]> read(Principal principal,@PathVariable String projectId,@PathVariable String id,@PathVariable String resource,HttpSession session){
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(access.access(UUID.fromString(principal.getName()),projectId,id,resource,null,null,session));
    }
    @GetMapping(value="/document",produces="text/html;charset=UTF-8")
    ResponseEntity<byte[]> document(Principal principal,@PathVariable String projectId,@PathVariable String id,HttpSession session,HttpServletResponse response){
        byte[] bytes=access.access(UUID.fromString(principal.getName()),projectId,id,"document",null,null,session);
        response.setHeader("Content-Security-Policy",RuntimeDocumentPolicy.CSP);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(bytes);
    }
    @PutMapping(value="/state",produces="application/json")
    ResponseEntity<byte[]> save(Principal principal,@PathVariable String projectId,@PathVariable String id,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,HttpSession session){
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(access.access(UUID.fromString(principal.getName()),projectId,id,"state",body,key,session));
    }
}
