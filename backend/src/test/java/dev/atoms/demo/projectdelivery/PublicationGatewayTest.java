package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.projectexecution.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicationGatewayTest {
    @Test void realHttpIsAnonymousButNeverForwardsPlatformCookiesAndNamespacesApplicationCookies() throws Exception {
        try (var f = new Fixture()) {
            f.gateway.tick(); String url = f.address();
            var cookies = new PublicationCookies(f.project);
            String own = cookies.outgoing(List.of("session=own")).get(0).split(";", 2)[0];
            String other = new PublicationCookies(UUID.randomUUID()).outgoing(List.of("session=other")).get(0).split(";", 2)[0];
            var response = f.send(url + "api/counter", "GET", null, Map.of("Cookie", "ATOMS_HTTP_SESSION=PRIVATE_CANARY; "+own+"; "+other));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(f.input.get().applicationCookies()).isEqualTo("session=own");
            assertThat(f.input.get().headers()).doesNotContainKey("cookie");
            assertThat(response.headers().allValues("Set-Cookie")).singleElement().asString()
                    .startsWith("ATOMS_APP_").contains("HttpOnly", "SameSite=Strict").doesNotContain("Domain=");
            assertThat(response.body()).doesNotContain("PRIVATE_CANARY");
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("worker-src 'none'");
        }
    }
    @Test void crossOriginWritesTraversalAndForgedDestinationsNeverReachTheApplication() throws Exception {
        try (var f = new Fixture()) {
            f.gateway.tick(); String url = f.address();
            assertThat(f.send(url+"api/counter", "POST", "{}", Map.of("Origin", "http://127.0.0.1:8443", "Content-Type", "application/json")).statusCode()).isEqualTo(403);
            assertThat(f.send(url+"api/%2e%2e/private", "GET", null, Map.of()).statusCode()).isEqualTo(422);
            assertThat(f.send(url+"actuator/env", "GET", null, Map.of()).statusCode()).isEqualTo(422);
            verify(f.execution, never()).exchange(any(), any(), any(), any(), any());
            assertThat(f.send(url+"api/counter", "POST", "{}", Map.of("Origin", url.substring(0,url.length()-1), "Content-Type", "application/json")).statusCode()).isEqualTo(200);
        }
    }
    @Test void stopAndConfigurationRevocationCloseTheActualListenerAndDoNotReacquireLatestRun() throws Exception {
        try (var f = new Fixture()) {
            f.gateway.tick(); String url=f.address();
            f.ready.set(List.of()); f.gateway.tick();
            assertThatThrownBy(() -> f.send(url, "GET", null, Map.of())).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(f::address).isInstanceOfSatisfying(ApiException.class, e->assertThat(e.code()).isEqualTo("PUBLICATION_NOT_READY"));
            f.ready.set(List.of(f.candidate)); f.gateway.tick(); assertThat(f.send(url,"GET",null,Map.of()).statusCode()).isEqualTo(200);
            f.env.setProperty("atoms.project-publication.enabled","false");
            assertThat(f.send(url,"GET",null,Map.of()).statusCode()).isEqualTo(503);
            f.gateway.tick(); assertThat(f.address()).isNull();
            assertThatThrownBy(() -> f.send(url,"GET",null,Map.of())).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void occupiedPortNeverProducesAPublishedAddress() throws Exception {
        try (var f = new Fixture(); var occupied = new ServerSocket(f.port, 1, InetAddress.getByName("127.0.0.1"))) {
            f.gateway.tick();
            assertThatThrownBy(f::address).isInstanceOfSatisfying(ApiException.class, e->assertThat(e.code()).isEqualTo("PUBLICATION_PORT_UNAVAILABLE"));
        }
    }
    @Test void lostProcessIsRejectedAndItsListenerIsClosed() throws Exception {
        try (var f = new Fixture()) {
            f.gateway.tick(); String url=f.address();
            when(f.execution.exchange(any(),any(),any(),any(),any())).thenThrow(new ApiException(409,"EXECUTION_ACCESS_REVOKED","PRIVATE_CANARY"));
            var response=f.send(url,"GET",null,Map.of());
            assertThat(response.statusCode()).isEqualTo(409); assertThat(response.body()).doesNotContain("PRIVATE_CANARY");
            when(f.execution.endpoint(any(),any(),any(),any())).thenThrow(new ApiException(409,"EXECUTION_ACCESS_REVOKED","PRIVATE_CANARY"));
            f.gateway.tick();
            assertThatThrownBy(() -> f.send(url,"GET",null,Map.of())).isInstanceOf(java.io.IOException.class);
        }
    }
    private static final class Fixture implements AutoCloseable {
        final UUID owner=UUID.randomUUID(), project=UUID.randomUUID(), run=UUID.randomUUID();
        final int port;
        final MockEnvironment env=new MockEnvironment().withProperty("atoms.project-publication.enabled","true")
                .withProperty("atoms.project-publication.host","127.0.0.1").withProperty("atoms.project-delivery.enabled","true")
                .withProperty("atoms.project-delivery.origin","http://127.0.0.1:8443");
        final ProjectExecutionAccess execution=mock(ProjectExecutionAccess.class);
        final ProjectExecutionAccess.RunningHandle handle=mock(ProjectExecutionAccess.RunningHandle.class);
        final AtomicReference<List<PublicationGateway.Candidate>> ready=new AtomicReference<>();
        final AtomicReference<ExecutionHttpRequest> input=new AtomicReference<>();
        final PublicationGateway.Candidate candidate;
        final PublicationGateway gateway;
        Fixture() throws Exception {
            try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
            candidate=new PublicationGateway.Candidate(owner,project,run,port-10000); ready.set(List.of(candidate));
            var identity=new ProjectExecutionAccess.RunningIdentity(run,UUID.randomUUID(),new ProjectExecutionAccess.BuildIdentity(project,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),1,"b".repeat(64),"c".repeat(64)));
            when(execution.running(eq(owner),eq(project),eq(run),any())).thenReturn(handle);
            when(execution.endpoint(eq(owner),eq(project),same(handle),any())).thenReturn(new ProjectExecutionAccess.RunningEndpoint(identity,candidate.internalPort()));
            var output=mock(ExecutionHttpResponse.class);
            when(output.status()).thenReturn(200); when(output.contentType()).thenReturn("application/json");
            when(output.bytes()).thenReturn(2); when(output.body()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
            when(output.applicationCookies()).thenReturn(List.of("session=result; Domain=evil.test; Path=/api"));
            when(execution.exchange(eq(owner),eq(project),same(handle),any(),any())).thenAnswer(call->{input.set(call.getArgument(3));return output;});
            gateway=new PublicationGateway(new PublicationSettings(env),new DeliverySettings(env),execution,ready::get);
        }
        String address(){return gateway.address(owner,project,run,candidate.internalPort());}
        HttpResponse<String> send(String url,String method,String body,Map<String,String> headers) throws Exception {
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)); headers.forEach(request::header);
            return HttpClient.newBuilder().proxy(new ProxySelector(){
                public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);} public void connectFailed(URI uri,SocketAddress socket,java.io.IOException e){}
            }).version(HttpClient.Version.HTTP_1_1).build().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close(){gateway.close();}
    }
}
