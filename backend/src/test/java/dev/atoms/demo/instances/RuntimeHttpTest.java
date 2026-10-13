package dev.atoms.demo.instances;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeHttpTest {
    @Test void rejectsRedirectsWrongExecutionAndOversizedResponses() throws Exception {
        UUID execution=UUID.randomUUID(),installation=UUID.randomUUID();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/health",request->{
            request.getResponseHeaders().set("X-Atoms-Execution-Id",execution.toString());
            request.getResponseHeaders().set("Location","http://169.254.169.254/");
            request.sendResponseHeaders(302,-1);request.close();
        });
        server.createContext("/api/app",request->{request.sendResponseHeaders(200,2);request.getResponseBody().write("{}".getBytes());request.close();});
        server.createContext("/api/state",request->{
            request.getResponseHeaders().set("X-Atoms-Execution-Id",execution.toString());
            request.sendResponseHeaders(200,2*1024*1024);try{request.getResponseBody().write(new byte[2*1024*1024]);}catch(java.io.IOException ignored){}request.close();
        });
        server.start();
        try {
            var http=new RuntimeHttp();int port=server.getAddress().getPort();
            for(String path:new String[]{"/api/health","/api/app","/api/state"})
                assertThatThrownBy(()->http.send(port,execution,installation,path,"GET",null,Map.of()))
                        .isInstanceOf(dev.atoms.demo.common.ApiException.class).hasMessageNotContaining("169.254").hasMessageNotContaining("127.0.0.1");
            assertThatThrownBy(()->http.send(port,execution,installation,"//evil.example","GET",null,Map.of())).isInstanceOf(IllegalArgumentException.class);
        } finally {server.stop(0);}
    }
}
