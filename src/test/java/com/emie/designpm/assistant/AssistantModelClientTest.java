package com.emie.designpm.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.recommendation.service.VisualAiSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AssistantModelClientTest {
    private VisualAiSettings.RuntimeConfig config(String provider, String dialect, boolean vision, String thinking) {
        return new VisualAiSettings.RuntimeConfig(
                "https://example.com/v1",
                "test-model",
                "test-key",
                true,
                provider,
                dialect,
                "测试配置",
                vision,
                "test-profile",
                thinking);
    }

    @Test
    void requestFormatAndThinkingOptionsFollowTheSelectedProfile() {
        var kimi = AssistantModelClient.requestBody(config("kimi", "kimi", true, "disabled"), List.of(), 1400);
        assertEquals(1400, kimi.get("max_completion_tokens"));
        assertEquals(Map.of("type", "disabled"), kimi.get("thinking"));
        assertFalse(kimi.containsKey("max_tokens"));
        var deepseek =
                AssistantModelClient.requestBody(config("deepseek", "deepseek", false, "disabled"), List.of(), 1400);
        assertEquals(1400, deepseek.get("max_tokens"));
        assertEquals(Map.of("type", "disabled"), deepseek.get("thinking"));
        var qwen = AssistantModelClient.requestBody(config("qwen", "qwen", true, "disabled"), List.of(), 500);
        assertEquals(500, qwen.get("max_completion_tokens"));
        assertEquals(false, qwen.get("enable_thinking"));
        assertFalse(qwen.containsKey("thinking"));
    }

    @Test
    void modelDefaultAndGenericProfilesNeverReceiveGuessedThinkingOptions() {
        for (String dialect : List.of("kimi", "deepseek", "openai", "compatible", "qwen")) {
            var body = AssistantModelClient.requestBody(config("custom", dialect, false, "auto"), List.of(), 1400);
            assertFalse(body.containsKey("thinking"));
            assertFalse(body.containsKey("enable_thinking"));
            assertFalse(body.toString().contains("test-key"));
        }
        var generic =
                AssistantModelClient.requestBody(config("custom", "compatible", false, "disabled"), List.of(), 1400);
        assertFalse(generic.containsKey("thinking"));
        assertFalse(generic.containsKey("enable_thinking"));
    }

    @Test
    void textOnlyProfileRefusesImagesBeforeAnyProviderRequest() {
        var client = new AssistantModelClient(new AssistantEndpointPolicy(""));
        var error = assertThrows(
                AssistantModelClient.ModelFailure.class,
                () -> client.describe(
                        config("custom", "compatible", false, "auto"), List.of("data:image/jpeg;base64,YWJj")));
        assertTrue(error.publicMessage().contains("不支持图片理解"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void trustedLanRequestsUseTheSelectedProfileForChatAndImages() throws Exception {
        String base = "http://192.168.200.118:8317/v1";
        var policy = new AssistantEndpointPolicy(base);
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> chat = mock(HttpResponse.class), image = mock(HttpResponse.class);
        when(chat.statusCode()).thenReturn(200);
        when(chat.body())
                .thenReturn("{\"choices\":[{\"message\":{\"content\":\"内网回答\"}}]}".getBytes(StandardCharsets.UTF_8));
        when(image.statusCode()).thenReturn(200);
        when(image.body())
                .thenReturn("{\"choices\":[{\"message\":{\"content\":\"{\\\"searchTerms\\\":[\\\"蓝色\\\"]}\"}}]}"
                        .getBytes(StandardCharsets.UTF_8));
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(chat), CompletableFuture.completedFuture(image));
        var client = new AssistantModelClient(http, policy);
        var config = new VisualAiSettings.RuntimeConfig(
                base,
                "lan-selected-model",
                "lan-profile-only-key",
                true,
                "custom",
                "compatible",
                "内网代理",
                true,
                "lan-profile",
                "auto");
        assertEquals("内网回答", client.reply(config, "测试问题", List.of()));
        assertEquals("蓝色", client.describe(config, List.of("data:image/jpeg;base64,YWJj")));
        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).sendAsync(sent.capture(), any(HttpResponse.BodyHandler.class));
        for (HttpRequest request : sent.getAllValues()) {
            assertEquals(base + "/chat/completions", request.uri().toString());
            assertEquals("POST", request.method());
            assertEquals(
                    "Bearer lan-profile-only-key",
                    request.headers().firstValue("Authorization").orElseThrow());
            String body = requestBody(request);
            assertEquals(
                    "lan-selected-model",
                    new ObjectMapper().readTree(body).path("model").asText());
            assertFalse(body.contains("lan-profile-only-key"));
            assertFalse(body.contains("server-only-secret"));
        }
    }

    @Test
    void untrustedRuntimeProfilesAreRejectedBeforeAnyRequestAndProductionClientNeverRedirects() {
        String base = "http://192.168.200.118:8317/v1";
        var policy = new AssistantEndpointPolicy(base);
        HttpClient http = mock(HttpClient.class);
        var client = new AssistantModelClient(http, policy);
        for (String endpoint : List.of(
                "http://192.168.200.118:8318/v1",
                "http://192.168.200.118:8317/v2",
                "http://192.168.200.119:8317/v1",
                "https://169.254.169.254/v1")) {
            var config = new VisualAiSettings.RuntimeConfig(endpoint, "model", "private-profile-key", true);
            assertThrows(IllegalArgumentException.class, () -> client.reply(config, "测试", List.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.describe(config, List.of("data:image/jpeg;base64,YWJj")));
        }
        var defaultPolicyClient = new AssistantModelClient(http, new AssistantEndpointPolicy(""));
        var lanConfig = new VisualAiSettings.RuntimeConfig(base, "model", "private-profile-key", true);
        assertThrows(IllegalArgumentException.class, () -> defaultPolicyClient.reply(lanConfig, "测试", List.of()));
        verifyNoInteractions(http);
        HttpClient productionHttp = (HttpClient) ReflectionTestUtils.getField(new AssistantModelClient(policy), "http");
        assertNotNull(productionHttp);
        assertEquals(HttpClient.Redirect.NEVER, productionHttp.followRedirects());
    }

    private String requestBody(HttpRequest request) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var body = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }

            public void onError(Throwable error) {
                body.completeExceptionally(error);
            }

            public void onComplete() {
                body.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return body.get(1, TimeUnit.SECONDS);
    }

    @Test
    void providerErrorsAreStaticAndNeverEchoPrivateBody() {
        var error = assertThrows(
                AssistantModelClient.ModelFailure.class,
                () -> AssistantModelClient.responseText(
                        429, "secret-key-and-provider-debug".getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.publicMessage().contains("额度或速率"));
        assertFalse(error.publicMessage().contains("secret"));
    }

    @Test
    void readsBoundedAnswersAndStructuredImageTerms() throws Exception {
        assertEquals(
                "你好",
                AssistantModelClient.responseText(
                        200, "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("海洋 蓝色", AssistantModelClient.searchTerms("```json\n{\"searchTerms\":[\"海洋\",\"蓝色\"]}\n```"));
        assertThrows(AssistantModelClient.ModelFailure.class, () -> AssistantModelClient.searchTerms("{}"));
        assertThrows(
                AssistantModelClient.ModelFailure.class, () -> AssistantModelClient.responseText(200, new byte[65537]));
    }

    @Test
    void streamingBodyStopsBeforeAllocatingUnboundedResponse() {
        var body = new AssistantResponseBody();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.allocate(65537)));
        verify(subscription).cancel();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
