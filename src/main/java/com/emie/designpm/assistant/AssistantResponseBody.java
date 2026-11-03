package com.emie.designpm.assistant;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class AssistantResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
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
            if (output.size() + buffer.remaining() > 65536) {
                subscription.cancel();
                body.completeExceptionally(new IllegalArgumentException("AI回答超过长度限制"));
                return;
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            output.writeBytes(bytes);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
        body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        body.complete(output.toByteArray());
    }
}
