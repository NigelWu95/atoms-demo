package dev.atoms.demo.instances;

import dev.atoms.demo.ApiBrowser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")
class InstanceApiIT {
    @LocalServerPort int port;
    @Test void emptyInstanceListIsOwnerOnlyAndNeverStartsProcesses() throws Exception {
        var owner=new ApiBrowser(port); owner.initialize();
        String id=owner.request("POST","/api/projects","{\"name\":\"独立实例边界\"}",UUID.randomUUID().toString()).body().path("id").asText();
        String path="/api/projects/"+id+"/instances";
        var response=owner.request("GET",path,null,null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("items").isArray()).isTrue();
        assertThat(response.body().path("items")).isEmpty();
        var other=new ApiBrowser(port);other.initialize();
        assertThat(other.request("GET",path,null,null).status()).isEqualTo(404);
        assertThat(owner.request("POST",path,"{\"command\":\"canary\"}",UUID.randomUUID().toString()).status()).isEqualTo(422);
        assertThat(owner.request("POST",path,"{}",UUID.randomUUID().toString(),"bad-csrf").status()).isEqualTo(403);
    }
}
