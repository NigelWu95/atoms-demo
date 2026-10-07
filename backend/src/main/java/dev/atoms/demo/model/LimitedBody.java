package dev.atoms.demo.model;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancel before buffering an excessive upstream response, including reasoning_content. */
final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int bytes;
    private boolean stopped;
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    public void onNext(List<ByteBuffer> chunks) {
        if (stopped) return;
        for (var chunk : chunks) {
            if (chunk.remaining() > 2 * 1024 * 1024 - bytes) {
                stopped = true; subscription.cancel();
                delegate.onError(new ModelException("MODEL_OUTPUT_TOO_LARGE", "模型响应超过大小限制。"));
                return;
            }
            bytes += chunk.remaining();
        }
        delegate.onNext(chunks);
    }
    public void onError(Throwable error) { if (!stopped) { stopped = true; delegate.onError(error); } }
    public void onComplete() { if (!stopped) { stopped = true; delegate.onComplete(); } }
}
