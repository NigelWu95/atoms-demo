package dev.atoms.demo.projectexecution;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExecutionHealthTest {
    @TempDir Path temporary;
    private final UUID run = UUID.randomUUID(), execution = UUID.randomUUID(), project = UUID.randomUUID();

    @Test void confirmsActualHttpIdentityAndReceiptExpiresWhenOwnedProcessStops() throws Exception {
        try (var fixture = launch("valid")) {
            var confirmed = awaitHealth(fixture);
            assertThat(confirmed.matches(run, execution, fixture.binding)).isTrue();
            assertThat(confirmed.matches(UUID.randomUUID(), execution, fixture.binding)).isFalse();
            assertThat(confirmed.matches(run, UUID.randomUUID(), fixture.binding)).isFalse();
            assertThat(confirmed.matches(run, execution, new ExecutionBindingRepository.Target(project, UUID.randomUUID(),
                    5432, "sample", "sample", fixture.binding.port(), 1, 2))).isFalse();
            assertThat(fixture.process.isServing()).isTrue();
            fixture.close();
            assertThat(confirmed.matches(run, execution, fixture.binding)).isFalse();
        }
    }

    @Test void refusesWrongIdentityMalformedDuplicateOversizedAndNonJsonBodies() throws Exception {
        for (String mode : List.of("wrong", "missing", "duplicate", "oversized", "html")) {
            try (var fixture = launch(mode)) {
                assertThatThrownBy(() -> awaitHealth(fixture)).as(mode)
                        .isInstanceOf(java.io.IOException.class).hasMessage("RUN_HEALTH_INVALID").hasNoCause();
                assertThat(fixture.process.isServing()).isFalse();
            }
        }
    }

    @Test void redirectIsNotFollowedAndRequestsNeverInheritProxyOrCredentials() throws Exception {
        var requests = new AtomicInteger();
        var external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        external.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        external.start();
        try (var fixture = launch("redirect:" + external.getAddress().getPort())) {
            assertThatThrownBy(() -> awaitHealth(fixture)).hasMessage("RUN_HEALTH_INVALID").hasNoCause();
            assertThat(requests).hasValue(0);
        } finally { external.stop(0); }
    }

    @Test void slowBodyIsBoundedByRemainingStartupBudgetAndLateStopCannotConfirmReady() throws Exception {
        try (var fixture = launch("slow")) {
            var budget = new ExecutionProcess.Deadline(Duration.ofMillis(150)); long started = System.nanoTime();
            assertThatThrownBy(() -> new ExecutionHealth().probe(fixture.process, run, execution, fixture.binding, budget))
                    .hasMessage("RUN_START_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(fixture.process.isServing()).isFalse();
        }
        try (var fixture = launch("valid")) {
            fixture.process.requestStop();
            assertThat(new ExecutionHealth().probe(fixture.process, run, execution, fixture.binding,
                    new ExecutionProcess.Deadline(Duration.ofSeconds(2)))).isNull();
        }
    }

    @Test void healthRequestsCannotHoldMetadataTransactionLocks() throws Exception {
        try (var fixture = launch("valid")) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> new ExecutionHealth().probe(fixture.process, run, execution, fixture.binding,
                        new ExecutionProcess.Deadline(Duration.ofSeconds(2)))).hasMessage("EXECUTION_TRANSACTION_ACTIVE");
            } finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThat(fixture.process.isServing()).isFalse();
        }
    }

    private ExecutionHealth.Confirmed awaitHealth(Fixture fixture) throws Exception {
        var health = new ExecutionHealth(); var budget = new ExecutionProcess.Deadline(Duration.ofSeconds(5));
        while (true) {
            var confirmed = health.probe(fixture.process, run, execution, fixture.binding, budget);
            if (confirmed != null) return confirmed;
            Thread.sleep(20);
        }
    }
    private Fixture launch(String mode) throws Exception {
        int port;
        try (var reserve = new java.net.ServerSocket()) { reserve.bind(new InetSocketAddress("127.0.0.1", 0)); port = reserve.getLocalPort(); }
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "--add-modules", "jdk.httpserver",
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(), Child.class.getName(),
                Integer.toString(port), run.toString(), execution.toString(), mode).directory(temporary.toFile());
        command.environment().clear();
        var process = ExecutionRunProcess.start(command, run, execution, new ExecutionProcess.Deadline(Duration.ofSeconds(10)), () -> {});
        var fixture = new Fixture(process, new ExecutionBindingRepository.Target(project, UUID.randomUUID(), 5432, "sample", "sample", port, 1, 2));
        try {
            long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (process.output().stdoutBytes() == 0 && System.nanoTime() < end) Thread.sleep(10);
            assertThat(process.output().stdoutBytes()).isPositive(); return fixture;
        } catch (Exception | AssertionError error) { fixture.close(); throw error; }
    }
    private record Fixture(ExecutionRunProcess process, ExecutionBindingRepository.Target binding) implements AutoCloseable {
        @Override public void close() throws Exception { process.requestStop(); assertThat(process.await(Duration.ofSeconds(12)).stopped()).isNotNull(); }
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
            server.createContext("/api/health", exchange -> {
                String mode = args[3];
                if (!exchange.getRequestMethod().equals("GET") || exchange.getRequestHeaders().containsKey("Authorization")
                        || exchange.getRequestHeaders().containsKey("Cookie")) { exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
                String body = "{\"status\":\"UP\",\"stage\":\"INITIALIZED\",\"runId\":\"" + args[1] + "\",\"executionId\":\"" + args[2] + "\"}";
                if (mode.equals("wrong")) body = body.replace(args[1], UUID.randomUUID().toString());
                if (mode.equals("missing")) body = "{\"status\":\"UP\"}";
                if (mode.equals("duplicate")) body = body.replace("{", "{\"status\":\"DOWN\",");
                if (mode.equals("oversized")) body = " ".repeat(8192) + body;
                exchange.getResponseHeaders().set("Content-Type", mode.equals("html") ? "text/html" : "application/json");
                if (mode.startsWith("redirect:")) {
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + mode.substring(9) + "/");
                    exchange.sendResponseHeaders(302, -1); exchange.close(); return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
                if (mode.equals("slow")) try { Thread.sleep(5000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start(); System.out.write(1); System.out.flush(); Thread.sleep(60000);
        }
    }
}
