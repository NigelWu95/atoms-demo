package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.ApiBrowser;
import dev.atoms.demo.projectexecution.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real servlet nonblocking IO and JDBC login; controlled execution responses exercise failure boundaries. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.project-delivery.enabled=true", "atoms.project-delivery.origin=http://127.0.0.1:5174"})
class DeliveryPreviewIT {
    @LocalServerPort int port;
    @MockitoSpyBean DeliveryGrants grants;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProjectExecutionAccess execution;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final UUID project = UUID.randomUUID(), run = UUID.randomUUID();
    private ApiBrowser browser;
    private String path;
    private UUID owner;
    private ProjectExecutionAccess.RunningHandle handle;

    @BeforeEach void issue() throws Exception {
        grants.clear(); browser = new ApiBrowser(port); owner = UUID.fromString(browser.initialize().body().path("userId").asText());
        handle = mock(ProjectExecutionAccess.RunningHandle.class);
        var identity = new ProjectExecutionAccess.RunningIdentity(run, UUID.randomUUID(), new ProjectExecutionAccess.BuildIdentity(
                project, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 1, "b".repeat(64), "c".repeat(64)));
        when(execution.running(eq(owner), eq(project), eq(run), any())).thenReturn(handle);
        when(execution.identity(eq(owner), eq(project), same(handle), any())).thenReturn(identity);
        when(execution.exchange(eq(owner), eq(project), same(handle), any(), any())).thenAnswer(call -> {
            ExecutionHttpRequest request = call.getArgument(3);
            return reply(200, request.path().startsWith("/api/") ? "application/json" : "text/html", "{}");
        });
        var created = browser.request("POST", "/api/projects/" + project + "/delivery/runs/" + run + "/previews", "{}", null);
        assertThat(created.status()).isEqualTo(201); path = created.body().path("iframeUrl").asText();
        clearInvocations(execution);
    }

    @Test void loadsWithoutPlatformCookiesAndPreservesJsonConflictAndApprovedHeaders() throws Exception {
        when(execution.exchange(eq(owner), eq(project), same(handle), any(), any())).thenAnswer(call -> {
            ExecutionHttpRequest request = call.getArgument(3);
            assertThat(request.headers()).doesNotContainKeys("cookie", "authorization", "x-csrf-token", "origin", "referer");
            if (request.write()) {
                assertThat(request.path()).isEqualTo("/api/todos");
                assertThat(request.body()).isEqualTo("{\"title\":\"draft\"}".getBytes(StandardCharsets.UTF_8));
                return reply(409, "application/json", "{\"error\":\"VERSION_CONFLICT\"}");
            }
            return reply(200, "text/html", "<!doctype html><h1>Preview</h1>");
        });
        var document = send("GET", "", null, Map.of());
        assertThat(document.statusCode()).isEqualTo(200); assertThat(document.body()).contains("<h1>Preview</h1>");
        assertThat(document.headers().firstValue("Content-Security-Policy").orElse("")).contains("sandbox allow-scripts", "frame-ancestors http://127.0.0.1:5174");
        assertThat(document.headers().firstValue("X-Frame-Options")).contains("SAMEORIGIN");
        var write = send("POST", "api/todos", "{\"title\":\"draft\"}", Map.of("Origin", "null", "Content-Type", "application/json",
                "Cookie", "PLATFORM=canary", "Authorization", "Bearer canary", "X-CSRF-TOKEN", "canary"));
        assertThat(write.statusCode()).isEqualTo(409); assertThat(write.body()).contains("VERSION_CONFLICT");
        assertThat(write.headers().firstValue("Access-Control-Allow-Origin")).contains("null");
        assertThat(write.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(write.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test void optionsValidatesOriginalRunWithoutForwardingAndManagementRemainsCsrfProtected() throws Exception {
        var options = send("OPTIONS", "api/todos", null, Map.of("Origin", "null", "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "content-type, if-match"));
        assertThat(options.statusCode()).isEqualTo(204); assertThat(options.body()).isEmpty();
        assertThat(options.headers().firstValue("Access-Control-Allow-Origin")).contains("null");
        verify(execution).identity(eq(owner), eq(project), same(handle), any());
        verify(execution, never()).exchange(any(), any(), any(), any(), any());
        assertThat(send("OPTIONS", "api/todos", null, Map.of("Origin", "null", "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization")).statusCode()).isEqualTo(422);
        var manager = browser.request("DELETE", "/api/session", null, null, null);
        assertThat(manager.status()).isEqualTo(403);
        assertThat(manager.raw().headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test void rejectsMalformedRequestsAndDoesNotEchoCapabilityEvenWhenFirewallRejects() throws Exception {
        for (String route : List.of("api/../me", "api/%2e%2e/me", "api/%252fme", "api//todos", "api/actuator", "other", "assets/a%2fb")) {
            var rejected = send("GET", route, null, Map.of("Origin", "null"));
            assertThat(rejected.statusCode()).isIn(400, 422);
            assertThat(rejected.body()).doesNotContain(path, path.substring(DeliveryGrants.PREFIX.length(), path.length() - 1));
        }
        assertThat(send("POST", "api/todos", "{broken", Map.of("Origin", "null", "Content-Type", "application/json")).statusCode()).isEqualTo(422);
        var foreign = send("GET", "", null, Map.of("Origin", "https://foreign.example"));
        assertThat(foreign.statusCode()).isEqualTo(422); assertThat(foreign.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        verifyNoInteractions(execution);
    }

    @Test void anonymousInvalidTokenIsClosedAndActualLogoutRevokesPreviewHttp() throws Exception {
        String valid = path; path = DeliveryGrants.PREFIX + "Z".repeat(43) + "/";
        var invalid = send("GET", "", null, Map.of("Origin", "null"));
        assertThat(invalid.statusCode()).isEqualTo(404); assertThat(invalid.body()).doesNotContain("Z".repeat(43));
        assertThat(invalid.headers().firstValue("Content-Security-Policy").orElse("")).contains("sandbox allow-scripts");
        assertThat(invalid.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        path = valid;
        assertThat(browser.request("DELETE", "/api/session", null, null).status()).isEqualTo(204);
        assertThat(send("GET", "", null, Map.of("Origin", "null")).statusCode()).isEqualTo(401);
        verifyNoInteractions(execution);
    }

    @Test void previewReadsDoNotRenewOriginalJdbcSessionEvenWhenBrowserSendsItsCookie() throws Exception {
        Long before = jdbc.queryForObject("SELECT last_access_time FROM spring_session WHERE principal_name = ?", Long.class, owner.toString());
        assertThat(browser.document(path).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT last_access_time FROM spring_session WHERE principal_name = ?", Long.class, owner.toString())).isEqualTo(before);
    }

    @Test void slowChunkedUploadTimesOutWithoutCallingApplicationAndReleasesItsSlot() throws Exception {
        try (var socket = slowUpload()) {
            long started = System.nanoTime();
            var text = new String(socket.getInputStream().readNBytes(1), StandardCharsets.US_ASCII);
            assertThat(text).isEqualTo("H");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(25), Duration.ofSeconds(38));
            var headers = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertThat(headers.readLine()).contains("504");
        }
        verify(execution, never()).exchange(any(), any(), any(), any(), any());
        assertThat(send("GET", "api/todos", null, Map.of("Origin", "null")).statusCode()).isEqualTo(200);
    }

    @Test void fourSlowUploadsHoldGrantCapacityAndDisconnectReleasesIt() throws Exception {
        var sockets = new ArrayList<Socket>();
        var admitted = new CountDownLatch(4);
        doAnswer(call -> { var lease = call.callRealMethod(); admitted.countDown(); return lease; }).when(grants).exchange(any());
        try {
            for (int i = 0; i < 4; i++) sockets.add(slowUpload());
            assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(send("GET", "api/todos", null, Map.of()).statusCode()).isEqualTo(429);
        } finally { for (var socket : sockets) socket.close(); }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int status;
        do { status = send("GET", "api/todos", null, Map.of()).statusCode(); if (status == 200) break; Thread.sleep(30); }
        while (System.nanoTime() < deadline);
        assertThat(status).isEqualTo(200);
    }

    private Socket slowUpload() throws Exception {
        var socket = new Socket("127.0.0.1", port); socket.setSoTimeout(40000);
        socket.getOutputStream().write(("POST " + path + "api/todos HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nOrigin: null\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush(); return socket;
    }
    private HttpResponse<String> send(String method, String suffix, String body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + suffix)).timeout(Duration.ofSeconds(40));
        headers.forEach(request::header);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private ExecutionHttpResponse reply(int status, String type, String body) {
        var reply = mock(ExecutionHttpResponse.class); when(reply.status()).thenReturn(status); when(reply.contentType()).thenReturn(type);
        when(reply.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8)); return reply;
    }
}
