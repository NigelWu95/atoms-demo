package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.atoms.demo.execution.OwnedProcess;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed-target startup handshake. Identity prevents accidental adoption, not hostile same-UID code. */
final class ExecutionHealth {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {}
            }).build();

    /** Not serializable and not reconstructable from IDs; validity still depends on the live owned process. */
    static final class Confirmed {
        private final ExecutionRunProcess process;
        private final UUID run, execution;
        private final ExecutionBindingRepository.Target binding;
        private Confirmed(ExecutionRunProcess process, UUID run, UUID execution, ExecutionBindingRepository.Target binding) {
            this.process = process; this.run = run; this.execution = execution; this.binding = binding;
        }
        boolean matches(UUID run, UUID execution, ExecutionBindingRepository.Target binding) {
            return this.run.equals(run) && this.execution.equals(execution) && this.binding.equals(binding) && process.isServing();
        }
    }

    /** Null means still starting or no longer eligible; no arbitrary URL, headers, proxy, or caller credentials. */
    Confirmed probe(ExecutionRunProcess process, UUID run, UUID execution, ExecutionBindingRepository.Target binding,
                    OwnedProcess.Budget budget) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IOException("EXECUTION_TRANSACTION_ACTIVE");
        Duration left = remaining(budget);
        Objects.requireNonNull(process); Objects.requireNonNull(run); Objects.requireNonNull(execution); Objects.requireNonNull(binding);
        if (!process.isAlive()) return null;
        Duration attempt = left.compareTo(Duration.ofSeconds(2)) < 0 ? left : Duration.ofSeconds(2);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + binding.port() + "/api/health"))
                .GET().timeout(attempt).header("Accept", "application/json").build();
        var future = CLIENT.sendAsync(request, ignored -> new Bounded());
        try {
            var response = future.get(attempt.toNanos(), TimeUnit.NANOSECONDS);
            remaining(budget);
            if (response.statusCode() == 503) return null;
            if (response.statusCode() != 200
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                    || !response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")) throw invalid();
            final JsonNode body;
            try { body = JSON.readTree(response.body()); } catch (IOException | RuntimeException error) { throw invalid(); }
            if (body == null || !body.isObject() || body.size() != 4 || !text(body, "status", "UP")
                    || !text(body, "stage", "INITIALIZED") || !text(body, "runId", run.toString())
                    || !text(body, "executionId", execution.toString())) throw invalid();
            remaining(budget);
            return process.confirmReady(run, execution, binding) ? new Confirmed(process, run, execution, binding) : null;
        } catch (TimeoutException error) { remaining(budget); return null; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("RUN_CANCELLED"); }
        catch (ExecutionException error) {
            remaining(budget);
            if (error.getCause() instanceof IOException io && "RUN_HEALTH_INVALID".equals(io.getMessage())) throw invalid();
            if (error.getCause() instanceof IOException) return null;
            throw invalid();
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private static boolean text(JsonNode body, String field, String expected) {
        var value = body.get(field); return value != null && value.isTextual() && expected.equals(value.textValue());
    }
    private static Duration remaining(OwnedProcess.Budget budget) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("RUN_CANCELLED");
        try {
            Duration left = budget.remaining();
            if (left == null || left.isZero() || left.isNegative()) throw new IOException();
            return left;
        } catch (IOException | RuntimeException error) { throw new IOException("RUN_START_TIMEOUT"); }
    }
    private static IOException invalid() { return new IOException("RUN_HEALTH_INVALID"); }
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        private boolean stopped;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (stopped) return;
            for (var chunk : chunks) {
                if (chunk.remaining() > 4096 - size) { stopped = true; subscription.cancel(); delegate.onError(invalid()); return; }
                size += chunk.remaining();
            }
            delegate.onNext(chunks);
        }
        @Override public void onError(Throwable error) { if (!stopped) { stopped = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!stopped) { stopped = true; delegate.onComplete(); } }
    }
}
