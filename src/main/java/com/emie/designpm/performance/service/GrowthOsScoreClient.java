package com.emie.designpm.performance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Backend-only client. Credentials and upstream response bodies never enter public errors. */
@Service
public class GrowthOsScoreClient {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String PATH = "/api/integrations/product-management/monthly-scores";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Identity(String feishuUserId, String name) {}

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    private final URI endpoint;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;
    private final boolean configured;

    @Autowired
    public GrowthOsScoreClient(
            @Value("${app.growth-os.base-url:}") String baseUrl,
            @Value("${app.growth-os.score-token:}") String token,
            @Value("${app.growth-os.allow-local-http:false}") boolean allowLocalHttp) {
        this(
                baseUrl,
                token,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Duration.ofSeconds(8),
                allowLocalHttp);
    }

    GrowthOsScoreClient(String baseUrl, String token, HttpClient http, Duration timeout) {
        this(baseUrl, token, http, timeout, false);
    }

    GrowthOsScoreClient(String baseUrl, String token, HttpClient http, Duration timeout, boolean allowLocalHttp) {
        this.configured = baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
        this.endpoint = endpoint(baseUrl, allowLocalHttp);
        this.token = token != null && token.matches("[\\x21-\\x7e]{32,4096}") ? token : null;
        this.http = http;
        this.timeout = timeout;
    }

    private static URI endpoint(String baseUrl, boolean allowLocalHttp) {
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean local = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host)
                    || (allowLocalHttp && "growth-os".equals(host));
            String path = uri.getRawPath();
            if (host.isEmpty()
                    || !("https".equals(scheme) || ("http".equals(scheme) && local))
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(path == null || path.isEmpty() || path.equals("/"))
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) return null;
            return new URI(scheme, null, host, uri.getPort(), PATH, null, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    public JsonNode fetch(String month, List<Identity> employees) {
        if (!isConfigured()) throw new Unavailable("成长周报评分接口尚未配置，请联系管理员");
        if (endpoint == null || token == null) throw new Unavailable("成长周报接口后端配置不正确，请联系管理员");
        if (employees == null || employees.isEmpty() || employees.size() > 100)
            throw new IllegalArgumentException("每批须查询1至100位人员");
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            byte[] body = JSON.writeValueAsBytes(Map.of("month", month, "employees", employees));
            if (body.length > 64 * 1024) throw new IllegalArgumentException("成长周报评分查询人数或资料过长，请拆分批次");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            future = http.sendAsync(request, ignored -> new BoundedBody());
            HttpResponse<byte[]> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new Unavailable("成长周报接口鉴权失败，请检查后端令牌");
            if (response.statusCode() != 200) throw new Unavailable("成长周报评分暂不可用，请稍后重试");
            JsonNode result = JSON.readTree(response.body());
            if (result == null
                    || !result.path("ok").isBoolean()
                    || !result.path("ok").booleanValue()
                    || !result.path("apiVersion").isTextual()
                    || !"1".equals(result.path("apiVersion").textValue())
                    || !result.path("month").isTextual()
                    || !month.equals(result.path("month").textValue())
                    || !result.path("data").isArray()) throw new Unavailable("成长周报返回的评分数据格式不正确");
            return result;
        } catch (Unavailable | IllegalArgumentException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new Unavailable("读取成长周报评分超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("读取成长周报评分已中断，请稍后重试");
        } catch (Exception ignored) {
            throw new Unavailable("成长周报评分暂不可用，请稍后重试");
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
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
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new Unavailable("成长周报评分响应过大，请缩小查询范围"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable ignored) {
            body.completeExceptionally(new Unavailable("成长周报评分暂不可用，请稍后重试"));
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
