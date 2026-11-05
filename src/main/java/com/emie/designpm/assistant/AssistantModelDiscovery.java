package com.emie.designpm.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Explicit-key, read-only model listing. Discovery never saves or borrows a model credential. */
@Service
public class AssistantModelDiscovery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 512 * 1024;
    private static final int MAX_MODELS = 300;
    private static final Set<String> KIMI_HOSTS = Set.of("api.moonshot.cn", "api.moonshot.ai");
    private static final Set<String> QWEN_HOSTS =
            Set.of("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com");
    private final HttpClient http;
    private final Consumer<String> validator;
    private final Semaphore slots = new Semaphore(2);

    @Autowired
    public AssistantModelDiscovery(AssistantEndpointPolicy endpoints) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                endpoints::validate);
    }

    AssistantModelDiscovery(HttpClient http, Consumer<String> validator) {
        this.http = http;
        this.validator = validator;
    }

    public static class DiscoveryFailure extends Exception {
        private final int status;

        public DiscoveryFailure(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public Map<String, Object> discover(Map<String, Object> body) throws DiscoveryFailure {
        String base = normalizeBase(string(body == null ? null : body.get("baseUrl")));
        String key = string(body == null ? null : body.get("apiKey"));
        if (key.isBlank() || key.length() > 4000 || key.chars().anyMatch(value -> value <= 32 || value >= 127))
            throw new IllegalArgumentException("获取模型时请填写该接口的有效 Key");
        if (!slots.tryAcquire()) throw new DiscoveryFailure(429, "正在获取其他模型列表，请稍后再试");
        try {
            validator.accept(base);
            String host = URI.create(base).getHost();
            String provider = provider(host), dialect = "custom".equals(provider) ? "compatible" : provider;
            boolean nativeQwen =
                    QWEN_HOSTS.contains(host) && URI.create(base).getPath().equals("/compatible-mode/v1");
            Map<String, Map<String, Object>> models = new LinkedHashMap<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean more = false;
            for (int page = 1; page <= (nativeQwen ? 3 : 1); page++) {
                URI endpoint = nativeQwen
                        ? URI.create("https://" + URI.create(base).getRawAuthority()
                                + "/api/v1/models?page_size=100&page_no=" + page)
                        : URI.create(base + "/models");
                JsonNode response = read(fetch(endpoint, key, deadline));
                JsonNode entries = response.path("data");
                if (!entries.isArray()) entries = response.path("output").path("models");
                if (!entries.isArray()) throw unsupported();
                boolean pageTruncated = false;
                for (JsonNode entry : entries) {
                    if (models.size() >= MAX_MODELS) {
                        pageTruncated = true;
                        break;
                    }
                    Map<String, Object> model = model(entry, key);
                    if (model != null) models.putIfAbsent((String) model.get("id"), model);
                }
                long total = response.path("output").path("total").asLong(0);
                more = pageTruncated
                        || response.path("has_more").asBoolean(false)
                        || nativeQwen && (long) page * 100 < total;
                if (!nativeQwen || entries.isEmpty() || (long) page * 100 >= total || models.size() >= MAX_MODELS)
                    break;
            }
            if (models.isEmpty()) throw new DiscoveryFailure(422, "未找到可用的对话模型，请检查接口权限，或在高级设置中手动填写模型");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("baseUrl", base);
            result.put("provider", provider);
            result.put("dialect", dialect);
            result.put("thinkingMode", "auto");
            result.put("models", List.copyOf(models.values()));
            result.put("truncated", more);
            result.put("message", more ? "模型列表较多，已显示前300个；其他模型可在高级设置中手动填写。" : "已获取模型列表，选择后即可保存。");
            return result;
        } finally {
            slots.release();
        }
    }

    private byte[] fetch(URI endpoint, String key, long deadline) throws DiscoveryFailure {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new DiscoveryFailure(504, "获取模型超时，请稍后重试");
        CompletableFuture<HttpResponse<byte[]>> future;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .timeout(Duration.ofNanos(remaining))
                    .GET()
                    .build();
            future = http.sendAsync(request, info -> new CatalogBody());
        } catch (RuntimeException invalid) {
            throw new DiscoveryFailure(502, "无法连接模型接口，请检查接口地址后重试");
        }
        try {
            HttpResponse<byte[]> response = future.get(remaining, TimeUnit.NANOSECONDS);
            switch (response.statusCode()) {
                case 200 -> {
                    return response.body();
                }
                case 401, 403 -> throw new DiscoveryFailure(400, "Key 无效或没有读取模型的权限，请检查后重试");
                case 404, 405 -> throw unsupported();
                case 429 -> throw new DiscoveryFailure(429, "接口请求过于频繁，请稍后重试");
                default -> throw new DiscoveryFailure(502, "暂时无法获取模型列表，请检查接口地址或稍后重试");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DiscoveryFailure(504, "获取模型已取消，请重新尝试");
        } catch (TimeoutException timeout) {
            throw new DiscoveryFailure(504, "获取模型超时，请稍后重试");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof java.net.http.HttpTimeoutException)
                throw new DiscoveryFailure(504, "获取模型超时，请稍后重试");
            throw new DiscoveryFailure(502, "无法读取模型列表，请检查接口连接或在高级设置中手动填写模型");
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static JsonNode read(byte[] body) throws DiscoveryFailure {
        if (body == null || body.length > MAX_BODY) throw new DiscoveryFailure(502, "模型列表过大，请在高级设置中手动填写模型");
        try {
            JsonNode result = JSON.readTree(body);
            if (result == null || !result.isObject()) throw unsupported();
            return result;
        } catch (Exception invalid) {
            throw unsupported();
        }
    }

    static String normalizeBase(String raw) {
        try {
            if (raw.isBlank() || raw.length() > 500 || raw.chars().anyMatch(Character::isISOControl))
                throw new Exception();
            URI uri = URI.create(raw);
            String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme)
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) throw new Exception();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = Optional.ofNullable(uri.getRawPath()).orElse("").replaceAll("/+$", "");
            if (path.endsWith("/chat/completions"))
                path = path.substring(0, path.length() - "/chat/completions".length());
            else if (path.endsWith("/models")) path = path.substring(0, path.length() - "/models".length());
            if (QWEN_HOSTS.contains(host) && (path.isEmpty() || path.equals("/api/v1"))) path = "/compatible-mode/v1";
            if (path.isEmpty() && (KIMI_HOSTS.contains(host) || host.equals("api.openai.com"))) path = "/v1";
            return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("请输入有效的模型接口地址");
        }
    }

    private static String provider(String host) {
        if (KIMI_HOSTS.contains(host)) return "kimi";
        if (QWEN_HOSTS.contains(host)) return "qwen";
        if (host.equals("api.deepseek.com")) return "deepseek";
        if (host.equals("api.openai.com")) return "openai";
        return "custom";
    }

    private static Map<String, Object> model(JsonNode entry, String secret) {
        String id = entry.isTextual()
                ? entry.asText()
                : entry.path("id").asText(entry.path("model").asText(""));
        String name = entry.path("name").asText(id);
        if (!safe(id, 150) || id.contains(secret) || name.contains(secret) || !textOutput(entry)) return null;
        if (!safe(name, 150)) name = id;
        JsonNode modalities = entry.path("input_modalities");
        if (!modalities.isArray()) modalities = entry.path("architecture").path("input_modalities");
        if (!modalities.isArray()) modalities = entry.path("inference_metadata").path("request_modality");
        boolean known = modalities.isArray(), vision = hasValue(modalities, "image");
        JsonNode supportsImage = entry.path("supports_image_in");
        if (supportsImage.isBoolean()) {
            known = true;
            vision = supportsImage.asBoolean();
        }
        JsonNode capabilities = capabilities(entry);
        if (!known && capabilities.isArray()) {
            known = true;
            vision = hasValue(capabilities, "vu");
        }
        return Map.of("id", id, "name", name, "visionSupported", vision, "visionKnown", known);
    }

    private static boolean textOutput(JsonNode entry) {
        JsonNode output = entry.path("output_modalities");
        if (!output.isArray()) output = entry.path("architecture").path("output_modalities");
        if (!output.isArray()) output = entry.path("inference_metadata").path("response_modality");
        if (output.isArray() && !output.isEmpty()) return hasValue(output, "text");
        String type = entry.path("type").asText("").toLowerCase(Locale.ROOT);
        if (Set.of("embedding", "embeddings", "rerank", "reranker", "image", "audio", "speech")
                .contains(type)) return false;
        JsonNode capabilities = capabilities(entry);
        return !capabilities.isArray()
                || capabilities.isEmpty()
                || hasValue(capabilities, "tg")
                || hasValue(capabilities, "vu")
                || hasValue(capabilities, "reasoning");
    }

    private static boolean hasValue(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (value.isTextual() && expected.equalsIgnoreCase(value.asText())) return true;
        return false;
    }

    private static JsonNode capabilities(JsonNode entry) {
        JsonNode result = entry.path("capabilities");
        return result.isArray() ? result : entry.path("inference_metadata").path("capabilities");
    }

    private static boolean safe(String value, int limit) {
        return !value.isBlank() && value.length() <= limit && value.chars().noneMatch(Character::isISOControl);
    }

    private static String string(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private static DiscoveryFailure unsupported() {
        return new DiscoveryFailure(422, "接口未提供可识别的模型列表，可在高级设置中手动填写模型");
    }

    static final class CatalogBody implements HttpResponse.BodySubscriber<byte[]> {
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
                if (output.size() + buffer.remaining() > MAX_BODY) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalArgumentException("模型列表超过读取限制"));
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
}
