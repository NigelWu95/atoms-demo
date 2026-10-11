package dev.atoms.standalone;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.atoms.standalone.StateHttpFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

class StateWriteIT {
    private StateHttpFixture app;
    @BeforeEach void setup() { app = new StateHttpFixture(); }
    @AfterEach void stop() { if (app != null) app.close(); }
    private String request(String value, long revision) {
        return "{\"loadedRuntimeId\":\"" + app.runtimeId() + "\",\"value\":" + value
                + ",\"expectedRevision\":" + revision + ",\"stateSchemaVersion\":1}";
    }
    private int records() { return app.jdbc().queryForObject("SELECT count(*) FROM standalone_app.idempotency_records", Integer.class); }

    @Test void savesAndReplaysTheOriginalResultEvenAfterLaterSavesWithVisitorIsolation() throws Exception {
        var a = app.new Browser().initialize();
        var b = app.new Browser().initialize();
        String first = request("{\"count\":1,\"note\":\"quote' and \\u0000\"}", 0);
        var saved = body(a.send("PUT", "/api/state", first, "key-1"));
        assertThat(saved.path("revision").asLong()).isEqualTo(1);
        assertThat(saved.path("value").path("note").asText()).isEqualTo("quote' and \0");
        assertThat(a.get()).isEqualTo(saved);
        assertThat(b.get().path("revision").asLong()).isZero();
        body(a.send("PUT", "/api/state", request("{\"count\":2}", 1), "key-2"));
        assertThat(body(a.send("PUT", "/api/state", first, "key-1"))).isEqualTo(saved);
        assertThat(a.get().path("revision").asLong()).isEqualTo(2);
        assertThat(body(b.send("PUT", "/api/state", first, "key-1"))).isEqualTo(saved);
        assertThat(records()).isEqualTo(3);
        app.jdbc().update("DELETE FROM standalone_app.guest_users WHERE id = ?", b.user);
        assertThat(records()).isEqualTo(2);
    }

    @Test void confirmedRejectionsAndInvalidInputsHaveNoStateOrIdempotencyEffects() throws Exception {
        var a = app.new Browser().initialize();
        String input = request("{}", 0);
        error(a.send("PUT", "/api/state", input, null), 422, "INVALID_INPUT");
        error(a.send("PUT", "/api/state", input.replace("\"value\":{}", "\"value\":{},\"guestId\":\"other\""), "x"), 422, "INVALID_INPUT");
        error(a.send("PUT", "/api/state", request("[]", 0), "x"), 422, "INVALID_INPUT");
        error(a.send("PUT", "/api/state", request("{}", 1), "x"), 409, "STATE_CONFLICT");
        error(a.send("PUT", "/api/state", input.replace(app.runtimeId(), "0".repeat(64)), "x"), 409, "STALE_APP_VERSION");
        String token = a.token;
        a.token = null;
        error(a.send("PUT", "/api/state", input, "x"), 403, "CSRF_REJECTED");
        a.token = token;
        assertThat(a.get().path("revision").asLong()).isZero();
        assertThat(records()).isZero();
        body(a.send("PUT", "/api/state", input, "x"));
        error(a.send("PUT", "/api/state", request("{\"different\":true}", 0), "x"), 409, "IDEMPOTENCY_PAYLOAD_MISMATCH");
        error(a.send("PUT", "/api/state", input, "new"), 409, "STATE_CONFLICT");
        assertThat(records()).isEqualTo(1);
        assertThat(a.get().path("revision").asLong()).isEqualTo(1);
    }

    @Test void sameIntentRacingAtInitialStateCommitsExactlyOnce() throws Exception {
        var a = app.new Browser().initialize();
        String input = request("{\"todos\":[\"first\"]}", 0);
        var results = race(() -> a.send("PUT", "/api/state", input, "race"),
                () -> a.send("PUT", "/api/state", input, "race"));
        assertThat(body(results.get(0))).isEqualTo(body(results.get(1)));
        assertThat(a.get().path("revision").asLong()).isEqualTo(1);
        assertThat(records()).isEqualTo(1);
    }

    @Test void differentIntentsRacingAtInitialStateProduceOneConflictWithoutOverwrite() throws Exception {
        var a = app.new Browser().initialize();
        var results = race(() -> a.send("PUT", "/api/state", request("{\"winner\":1}", 0), "one"),
                () -> a.send("PUT", "/api/state", request("{\"winner\":2}", 0), "two"));
        assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
        var accepted = results.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
        assertThat(a.get()).isEqualTo(body(accepted));
        assertThat(records()).isEqualTo(1);
    }

    @Test void sameKeyDifferentBodiesRacingCannotBothSucceed() throws Exception {
        var a = app.new Browser().initialize();
        var results = race(() -> a.send("PUT", "/api/state", request("{\"winner\":1}", 0), "same"),
                () -> a.send("PUT", "/api/state", request("{\"winner\":2}", 0), "same"));
        assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
        error(results.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "IDEMPOTENCY_PAYLOAD_MISMATCH");
        assertThat(records()).isEqualTo(1);
    }

    @Test void failedResultInsertRollsBackStateAndAllowsTheOriginalIntentToRetry() throws Exception {
        var a = app.new Browser().initialize();
        String input = request("{\"count\":9}", 0);
        app.jdbc().execute("ALTER TABLE standalone_app.idempotency_records ADD CONSTRAINT result_failure_canary CHECK (intent_key <> 'rollback')");
        try {
            error(a.send("PUT", "/api/state", input, "rollback"), 503, "SERVICE_UNAVAILABLE");
            assertThat(a.get().path("revision").asLong()).isZero();
            assertThat(records()).isZero();
        } finally { app.jdbc().execute("ALTER TABLE standalone_app.idempotency_records DROP CONSTRAINT result_failure_canary"); }
        assertThat(body(a.send("PUT", "/api/state", input, "rollback")).path("revision").asLong()).isEqualTo(1);
    }

    @Test void committedReplaysSurviveRestartAndChangedRuntimeBeforeVersionCheck() throws Exception {
        var a = app.new Browser().initialize();
        String input = request("{\"count\":9}", 0);
        var saved = body(a.send("PUT", "/api/state", input, "restart"));
        var files = TestApplication.files();
        files.put("application/styles.css", "body { color: black; }".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        app.close();
        app.start(TestApplication.resources(files));
        assertThat(body(a.send("PUT", "/api/state", input, "restart"))).isEqualTo(saved);
        error(a.send("PUT", "/api/state", input, "new-intent"), 409, "STALE_APP_VERSION");
        assertThat(a.get()).isEqualTo(saved);
        assertThat(records()).isEqualTo(1);
    }

    @Test void boundedLockWaitDoesNotBlockAnotherVisitorOrLeaveAPartialResult() throws Exception {
        var a = app.new Browser().initialize();
        var b = app.new Browser().initialize();
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = TestDatabase.dataSource(TestDatabase.load()).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM standalone_app.guest_users WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, a.user);
                statement.executeQuery().close();
            }
            var waiting = executor.submit(() -> a.send("PUT", "/api/state", request("{}", 0), "locked"));
            assertThat(body(b.send("PUT", "/api/state", request("{}", 0), "locked")).path("revision").asInt()).isEqualTo(1);
            error(waiting.get(7, TimeUnit.SECONDS), 503, "SERVICE_UNAVAILABLE");
            connection.rollback();
        } finally { executor.shutdownNow(); }
        assertThat(a.get().path("revision").asLong()).isZero();
        assertThat(records()).isEqualTo(1);
        assertThat(body(a.send("PUT", "/api/state", request("{}", 0), "locked")).path("revision").asInt()).isEqualTo(1);
    }

    @Test void maximumUtf8ValueAndRevisionAreAcceptedButCannotOverflow() throws Exception {
        var a = app.new Browser().initialize();
        String exact = "{\"x\":\"" + "a".repeat(262136) + "\"}";
        body(a.send("PUT", "/api/state", request(exact, 0), "limit"));
        error(a.send("PUT", "/api/state", request(exact.replace("\"x\"", "\"xx\""), 1), "over"), 422, "INVALID_INPUT");
        app.jdbc().update("UPDATE standalone_app.app_state SET revision = 9007199254740990 WHERE guest_id = ?", a.user);
        assertThat(body(a.send("PUT", "/api/state", request("{}", 9007199254740990L), "last")).path("revision").asLong())
                .isEqualTo(9007199254740991L);
        error(a.send("PUT", "/api/state", request("{}", 9007199254740991L), "overflow"), 422, "INVALID_INPUT");
        assertThat(records()).isEqualTo(2);
    }

    private List<HttpResponse<String>> race(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var pending = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("race did not start");
                return action.call();
            })).toList();
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(pending.get(0).get(10, TimeUnit.SECONDS), pending.get(1).get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
