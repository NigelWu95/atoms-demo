package dev.atoms.demo.run;

import dev.atoms.demo.ApiBrowser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DEEPSEEK_API_KEY=test-only-key", "GENERATION_MAX_MODEL_CALLS=100000", "atoms.generation.worker-enabled=false"})
@ActiveProfiles("test")
@Timeout(25)
class EventReplayIT {
    @LocalServerPort int port;
    @Autowired RunRepository runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final java.util.Map<String, ApiBrowser> created = new java.util.HashMap<>();
    @AfterEach void cleanup() throws Exception {
        for (var entry : created.entrySet()) entry.getValue().request("PUT", entry.getKey() + "/cancellation", "{}", null);
    }
    private String queued(ApiBrowser browser) throws Exception {
        browser.initialize();
        String project = browser.request("POST", "/api/projects", "{\"name\":\"SSE 协议测试\"}", UUID.randomUUID().toString()).body().path("id").asText();
        var response = browser.request("POST", "/api/projects/" + project + "/runs",
                "{\"prompt\":\"仅测试事件，不调用模型\",\"intent\":\"CREATE\",\"baseVersionId\":null,\"expectedProjectRevision\":0}", UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(202);
        String path = "/api/runs/" + response.body().path("runId").asText();
        created.put(path, browser);
        return path;
    }
    private String frame(BufferedReader reader) throws Exception {
        StringBuilder frame = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            if (line.isEmpty()) { if (!frame.isEmpty()) return frame.toString(); }
            else frame.append(line).append('\n');
        }
        return frame.toString();
    }
    @Test void flushesBeforeCompletionAndReplaysCancellationAfterDisconnectWithoutCancellingTask() throws Exception {
        var browser = new ApiBrowser(port); String run = queued(browser);
        var response = browser.events(run + "/events?after=0", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(frame(reader)).contains(":connected");
            assertThat(frame(reader)).contains("id:1", "event:run.queued", "\"seq\":1");
        }
        assertThat(browser.get(run).body().path("status").asText()).isEqualTo("QUEUED");
        browser.request("PUT", run + "/cancellation", "{}", null);
        var replay = browser.events(run + "/events?after=0", "1");
        try (var body = replay.body()) {
            String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("event:run.queued").contains("event:run.completed", "\"lastEventSeq\":2", "CANCELLED");
        }
        var finished = browser.events(run + "/events?after=2", null);
        try (var body = finished.body()) { assertThat(finished.statusCode()).isEqualTo(204); }
    }
    @Test void rejectsOtherOwnersAndInvalidCursorsBeforeStreaming() throws Exception {
        var browser = new ApiBrowser(port); String run = queued(browser);
        var stranger = new ApiBrowser(port); stranger.initialize();
        for (ApiBrowser denied : new ApiBrowser[]{stranger, new ApiBrowser(port)}) {
            var response = denied.events(run + "/events", null);
            try (var body = response.body()) { assertThat(response.statusCode()).isEqualTo(denied == stranger ? 404 : 401); }
        }
        for (String cursor : new String[]{"-1", "01", "1.5", "2", "9007199254740992", "abc"}) {
            var response = browser.events(run + "/events?after=" + cursor, null);
            try (var body = response.body()) {
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).contains("INVALID_CURSOR");
            }
        }
        browser.request("PUT", run + "/cancellation", "{}", null);
    }
    @Test void tailsNewCommittedEventsOverTheSameConnection() throws Exception {
        var browser = new ApiBrowser(port); String run = queued(browser);
        var response = browser.events(run + "/events?after=1", null);
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(frame(reader)).contains(":connected");
            browser.request("PUT", run + "/cancellation", "{}", null);
            assertThat(frame(reader)).contains("id:2", "event:run.completed", "CANCELLED");
            assertThat(reader.readLine()).isNull();
        }
    }
    @Test void paginatesReplayInOrderAndNeverPublishesRolledBackEvents() throws Exception {
        var browser = new ApiBrowser(port); String path = queued(browser);
        var run = jdbc.queryForObject("SELECT * FROM runs WHERE id = ?", RunRepository.ROW, UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            runs.lock(run.ownerId(), run.id().toString());
            for (int i = 0; i < 105; i++) runs.event(run, "stage.started", java.util.Map.of("stage", "BUILDING"));
        });
        tx.executeWithoutResult(status -> {
            runs.lock(run.ownerId(), run.id().toString());
            runs.event(run, "stage.started", java.util.Map.of("stage", "must-not-be-sent"));
            status.setRollbackOnly();
        });
        browser.request("PUT", path + "/cancellation", "{}", null);
        var response = browser.events(path + "/events", null);
        try (var body = response.body()) {
            String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("must-not-be-sent").contains("\"lastEventSeq\":107");
            assertThat(text.lines().filter(line -> line.startsWith("id:")).map(line -> Long.parseLong(line.substring(3))).toList())
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 107).boxed().toList());
        }
    }
    @Test void heartbeatDoesNotAllocateAnEventOrCancelTheRun() throws Exception {
        var browser = new ApiBrowser(port); String run = queued(browser);
        var response = browser.events(run + "/events?after=1", null);
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(frame(reader)).contains(":connected");
            assertThat(frame(reader)).contains(":heartbeat").doesNotContain("id:");
            assertThat(browser.get(run).body().path("lastEventSeq").asLong()).isEqualTo(1);
            assertThat(browser.get(run).body().path("status").asText()).isEqualTo("QUEUED");
        }
    }
    @Test void logoutClosesAnAlreadyOpenStreamWithoutCancellingGeneration() throws Exception {
        var browser = new ApiBrowser(port); String path = queued(browser);
        var run = jdbc.queryForObject("SELECT * FROM runs WHERE id = ?", RunRepository.ROW,
                UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
        var response = browser.events(path + "/events?after=1", null);
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(frame(reader)).contains(":connected");
            assertThat(browser.request("DELETE", "/api/session", null, null).status()).isEqualTo(204);
            assertThat(frame(reader)).isEmpty();
            assertThat(runs.get(run.ownerId(), run.id().toString()).status()).isEqualTo("QUEUED");
        } finally {
            // Only the test-created row: no user jobs are touched by this cleanup.
            jdbc.update("UPDATE runs SET status = 'CANCELLED' WHERE id = ?", run.id());
            created.remove(path);
        }
    }
}
