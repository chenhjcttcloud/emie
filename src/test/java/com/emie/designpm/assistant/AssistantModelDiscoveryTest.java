package com.emie.designpm.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantModelDiscoveryTest {
    private static final String KEY = "discovery-fixture-secret";

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    @SuppressWarnings("unchecked")
    private HttpClient http(int status, String body) {
        HttpClient http = mock(HttpClient.class);
        var reply = CompletableFuture.completedFuture(response(status, body));
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(reply);
        return http;
    }

    private Map<String, Object> request(String base) {
        return Map.of("baseUrl", base, "apiKey", KEY);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> models(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("models");
    }

    @Test
    void normalizesCommonEndpointFormsWithoutChangingCustomPrefixes() {
        assertEquals("https://api.moonshot.cn/v1", AssistantModelDiscovery.normalizeBase("https://API.MOONSHOT.CN/"));
        assertEquals(
                "https://api.deepseek.com", AssistantModelDiscovery.normalizeBase("https://api.deepseek.com/models/"));
        assertEquals(
                "https://api.openai.com/v1",
                AssistantModelDiscovery.normalizeBase("https://api.openai.com/v1/chat/completions"));
        assertEquals(
                "https://gateway.example/tenant/v1",
                AssistantModelDiscovery.normalizeBase("https://gateway.example/tenant/v1/models"));
        assertEquals(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                AssistantModelDiscovery.normalizeBase("https://dashscope.aliyuncs.com/api/v1/models"));
        for (String suffix : List.of("", "/", "/models/", "/chat/completions")) {
            assertEquals(
                    "http://192.168.200.118:8317/v1",
                    AssistantModelDiscovery.normalizeBase("http://192.168.200.118:8317/v1" + suffix));
        }
        for (String bad : List.of(
                "file:///etc/passwd",
                "https://secret@example.com/v1",
                "https://example.com/v1?key=secret",
                "https://example.com/#fragment",
                "https://example.com:0/v1",
                "https://example.com:65536/v1")) {
            var failure =
                    assertThrows(IllegalArgumentException.class, () -> AssistantModelDiscovery.normalizeBase(bad));
            assertFalse(failure.getMessage().contains("secret"));
        }
    }

    @Test
    void rejectsPrivateEndpointsAndInvalidKeysBeforeSendingAnything() {
        HttpClient http = mock(HttpClient.class);
        var discovery = new AssistantModelDiscovery(http, new AssistantEndpointPolicy("")::validate);
        for (String base : List.of(
                "https://127.0.0.1/v1",
                "https://localhost/v1",
                "https://192.168.1.2/v1",
                "https://169.254.169.254/v1",
                "http://192.168.200.118:8317/v1",
                "http://example.com",
                "https://example.com:8443/v1"))
            assertThrows(IllegalArgumentException.class, () -> discovery.discover(request(base)));
        for (String key : List.of("", "secret\nKey", "secret Key", "密钥-fixture", "a".repeat(4001)))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> discovery.discover(Map.of("baseUrl", "https://example.com/v1", "apiKey", key)));
        verifyNoInteractions(http);
    }

    @Test
    void trustedLanCatalogUsesOnlyTheExplicitKeyAtTheConfiguredBase() throws Exception {
        String base = "http://192.168.200.118:8317/v1";
        var http = http(200, "{\"data\":[{\"id\":\"lan-model\",\"input_modalities\":[\"text\"]}]}");
        var discovery = new AssistantModelDiscovery(http, new AssistantEndpointPolicy(base)::validate);
        var result = discovery.discover(request(base + "/chat/completions"));
        assertEquals(base, result.get("baseUrl"));
        assertEquals("custom", result.get("provider"));
        assertEquals("compatible", result.get("dialect"));
        assertEquals("lan-model", models(result).getFirst().get("id"));
        assertFalse(result.toString().contains(KEY));
        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(base + "/models", sent.getValue().uri().toString());
        assertEquals("GET", sent.getValue().method());
        assertEquals(
                "Bearer " + KEY,
                sent.getValue().headers().firstValue("Authorization").orElseThrow());
        assertTrue(sent.getValue().bodyPublisher().isEmpty());
    }

    @Test
    void lanTrustDoesNotAllowOtherHostsPortsSchemesOrPaths() {
        HttpClient http = mock(HttpClient.class);
        var discovery = new AssistantModelDiscovery(
                http, new AssistantEndpointPolicy("http://192.168.200.118:8317/v1")::validate);
        for (String base : List.of(
                "http://192.168.200.119:8317/v1",
                "http://192.168.200.118:8318/v1",
                "https://192.168.200.118:8317/v1",
                "http://192.168.200.118:8317/v2",
                "http://192.168.200.118:8317/v1evil",
                "http://192.168.200.118:8317/v1/../admin")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> discovery.discover(request(base)));
            assertFalse(failure.getMessage().contains(KEY));
        }
        verifyNoInteractions(http);
    }

    @Test
    void usesOnlyExplicitKeyAndReadsModernCapabilitiesWithoutLeakingCredentials() throws Exception {
        var http = http(
                200,
                """
                {"data":[
                  {"id":"text-model","name":"Text Model","input_modalities":["text"]},
                  {"id":"deepseek-vision","name":"Image Model","input_modalities":["text","image"]},
                  {"id":"kimi-image","supports_image_in":true},
                  {"id":"router-image","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
                  {"id":"unknown-model"}, {"id":"text-model"},
                  {"id":"image-output","output_modalities":["image"]},
                  {"id":"embed","type":"embedding"},
                  {"id":"discovery-fixture-secret"},
                  {"id":"secret-echo","name":"discovery-fixture-secret"}
                ]}
                """);
        var discovery = new AssistantModelDiscovery(http, base -> {});
        var result = discovery.discover(request("https://api.deepseek.com/v1/chat/completions"));
        assertEquals("deepseek", result.get("provider"));
        assertEquals("deepseek", result.get("dialect"));
        assertEquals("auto", result.get("thinkingMode"));
        assertEquals(5, models(result).size());
        assertEquals(false, models(result).getFirst().get("visionSupported"));
        assertEquals(true, models(result).getFirst().get("visionKnown"));
        assertEquals("Image Model", models(result).get(1).get("name"));
        assertEquals(true, models(result).get(1).get("visionSupported"));
        assertEquals(true, models(result).get(2).get("visionSupported"));
        assertEquals(true, models(result).get(3).get("visionSupported"));
        assertEquals(false, models(result).getLast().get("visionKnown"));
        assertFalse(result.toString().contains(KEY));
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(
                "https://api.deepseek.com/v1/models", request.getValue().uri().toString());
        assertEquals("GET", request.getValue().method());
        assertEquals(
                "Bearer " + KEY,
                request.getValue().headers().firstValue("Authorization").orElseThrow());
        assertTrue(request.getValue().bodyPublisher().isEmpty());
    }

    @Test
    void customHostsNeverBecomeAnOfficialProviderBasedOnNames() throws Exception {
        var discovery = new AssistantModelDiscovery(http(200, "{\"data\":[{\"id\":\"kimi-k2.6\"}]}"), base -> {});
        var result = discovery.discover(request("https://api.moonshot.cn.evil.example/v1"));
        assertEquals("custom", result.get("provider"));
        assertEquals("compatible", result.get("dialect"));
        assertEquals(false, models(result).getFirst().get("visionSupported"));
        assertThrows(
                IllegalArgumentException.class, () -> discovery.discover(Map.of("baseUrl", "https://example.com/v1")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void qwenUsesSameHostNativeCatalogAndBoundedPagination() throws Exception {
        var http = mock(HttpClient.class);
        var first = CompletableFuture.completedFuture(
                response(
                        200,
                        """
                {"output":{"total":101,"models":[{"model":"qwen-vl-model","name":"Qwen Vision","capabilities":["TG","VU"],"inference_metadata":{"request_modality":["Text","Image"],"response_modality":["Text"]}}]}}
                """));
        var second = CompletableFuture.completedFuture(
                response(
                        200,
                        """
                {"output":{"total":101,"models":[{"model":"qwen-text-model","inference_metadata":{"request_modality":["Text"],"response_modality":["Text"]}}]}}
                """));
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(first, second);
        var result = new AssistantModelDiscovery(http, base -> {})
                .discover(request("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("qwen", result.get("provider"));
        assertEquals(2, models(result).size());
        assertEquals(true, models(result).getFirst().get("visionSupported"));
        assertEquals(false, result.get("truncated"));
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).sendAsync(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(
                "https://dashscope.aliyuncs.com/api/v1/models?page_size=100&page_no=1",
                requests.getAllValues().getFirst().uri().toString());
        assertEquals(
                "https://dashscope.aliyuncs.com/api/v1/models?page_size=100&page_no=2",
                requests.getAllValues().getLast().uri().toString());
    }

    @Test
    void rejectsUnreadableCatalogsAndCapsOutput() throws Exception {
        for (String malformed : List.of(
                "",
                "null",
                "[]",
                "{}",
                "not-json",
                "{\"data\":[]}",
                "{\"data\":[{\"id\":\"x\",\"output_modalities\":[\"audio\"]}]}")) {
            var failure =
                    assertThrows(AssistantModelDiscovery.DiscoveryFailure.class, () -> new AssistantModelDiscovery(
                                    http(200, malformed), base -> {})
                            .discover(request("https://example.com/v1")));
            assertEquals(422, failure.status());
        }
        var entries = new ArrayList<String>();
        for (int i = 0; i < 350; i++) entries.add("{\"id\":\"model-" + i + "\"}");
        var result = new AssistantModelDiscovery(
                        http(200, "{\"data\":[" + String.join(",", entries) + "]}"), base -> {})
                .discover(request("https://example.com/v1"));
        assertEquals(300, models(result).size());
        assertEquals(true, result.get("truncated"));
        var failure = assertThrows(AssistantModelDiscovery.DiscoveryFailure.class, () -> new AssistantModelDiscovery(
                        http(200, "x".repeat(512 * 1024 + 1)), base -> {})
                .discover(request("https://example.com/v1")));
        assertEquals(502, failure.status());
    }

    @Test
    void providerFailuresAndRedirectsDoNotEchoTheResponseBody() {
        for (int status : List.of(301, 302, 401, 403, 404, 405, 429, 500)) {
            var failure =
                    assertThrows(AssistantModelDiscovery.DiscoveryFailure.class, () -> new AssistantModelDiscovery(
                                    http(status, KEY), base -> {})
                            .discover(request("https://example.com/v1")));
            assertEquals(
                    status == 401 || status == 403
                            ? 400
                            : status == 404 || status == 405 ? 422 : status == 429 ? 429 : 502,
                    failure.status());
            assertFalse(failure.getMessage().contains(KEY));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutAndSynchronousTransportErrorsAreSanitizedAndCancelTheRequest() throws Exception {
        var http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> future = mock(CompletableFuture.class);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(future);
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException(KEY));
        var failure = assertThrows(
                AssistantModelDiscovery.DiscoveryFailure.class,
                () -> new AssistantModelDiscovery(http, base -> {}).discover(request("https://example.com/v1")));
        assertEquals(504, failure.status());
        assertFalse(failure.getMessage().contains(KEY));
        verify(future).cancel(true);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IllegalArgumentException(KEY));
        var syncFailure = assertThrows(
                AssistantModelDiscovery.DiscoveryFailure.class,
                () -> new AssistantModelDiscovery(http, base -> {}).discover(request("https://example.com/v1")));
        assertEquals(502, syncFailure.status());
        assertFalse(syncFailure.getMessage().contains(KEY));
    }

    @Test
    void streamingCatalogStopsBeforeAcceptingAnUnboundedBody() {
        var body = new AssistantModelDiscovery.CatalogBody();
        var subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.allocate(512 * 1024 + 1)));
        verify(subscription).cancel();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTwoCatalogRequestsCanRunAtOnce() throws Exception {
        var http = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        var started = new CountDownLatch(2);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    return pending;
                });
        var service = new AssistantModelDiscovery(http, base -> {});
        var executor = Executors.newFixedThreadPool(2);
        var reply = response(200, "{\"data\":[{\"id\":\"fixture-model\"}]}");
        try {
            var first = executor.submit(() -> service.discover(request("https://example.com/v1")));
            var second = executor.submit(() -> service.discover(request("https://example.com/v1")));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var failure = assertThrows(
                    AssistantModelDiscovery.DiscoveryFailure.class,
                    () -> service.discover(request("https://example.com/v1")));
            assertEquals(429, failure.status());
            pending.complete(reply);
            assertEquals(1, models(first.get(3, TimeUnit.SECONDS)).size());
            assertEquals(1, models(second.get(3, TimeUnit.SECONDS)).size());
            verify(http, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } finally {
            pending.complete(reply);
            executor.shutdownNow();
        }
    }
}
