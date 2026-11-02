package com.emie.designpm.recommendation.service;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Limit response memory while it arrives, before JSON parsing. */
final class BoundedAiResponse implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (bytes.size() + buffer.remaining() > 262144) {
                subscription.cancel();
                body.completeExceptionally(new IllegalArgumentException("AI 返回内容过长"));
                return;
            }
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            bytes.writeBytes(data);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
        body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
        body.complete(bytes.toByteArray());
    }
}
