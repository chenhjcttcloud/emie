package com.emie.designpm.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.recommendation.service.VisualAiSettings;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

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
        var client = new AssistantModelClient();
        var error = assertThrows(
                AssistantModelClient.ModelFailure.class,
                () -> client.describe(
                        config("custom", "compatible", false, "auto"), List.of("data:image/jpeg;base64,YWJj")));
        assertTrue(error.publicMessage().contains("不支持图片理解"));
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
