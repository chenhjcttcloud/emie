package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class VisualAiClientTest {
    @Test
    void createsVisionPayloadWithoutCredentialsOrExternalFileUrls() throws Exception {
        var config = new VisualAiSettings.RuntimeConfig("https://api.moonshot.cn/v1", "kimi-k2.6", "test-secret", true);
        String body = VisualAiClient.requestBody(config, List.of("data:image/jpeg;base64,YWJj"));
        var json = new ObjectMapper().readTree(body);
        assertEquals("kimi-k2.6", json.path("model").asText());
        assertEquals(
                "image_url",
                json.path("messages").get(1).path("content").get(1).path("type").asText());
        assertFalse(body.contains("test-secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> VisualAiClient.requestBody(config, List.of("https://private/file.png")));
        assertThrows(
                IllegalArgumentException.class,
                () -> VisualAiClient.requestBody(
                        config, List.of("data:image/jpeg;base64," + "a".repeat(8 * 1024 * 1024))));
    }

    @Test
    void acceptsOnlyBoundedDescriptiveStructuredData() throws Exception {
        var profile = VisualAiClient.parseProfile(
                "```json\n{\"summary\":\"<b>蓝色包装</b>\",\"themes\":[\"海洋\",\"海洋\",42],\"elements\":[\"鲸鱼\"],\"styles\":[\"简约\"],\"colors\":[\"蓝\"],\"keywords\":[\"礼盒\"]}\n```");
        assertEquals("蓝色包装", profile.summary());
        assertEquals(List.of("海洋"), profile.themes());
        assertThrows(IllegalArgumentException.class, () -> VisualAiClient.parseProfile("{}"));
        assertThrows(IllegalArgumentException.class, () -> VisualAiClient.parseProfile("a".repeat(32769)));
        var limited = VisualAiClient.parseProfile(
                "{\"summary\":\"" + "a".repeat(400) + "\",\"keywords\":[\"" + "x".repeat(100) + "\"]}");
        assertEquals(240, limited.summary().length());
        assertEquals(60, limited.keywords().getFirst().length());
    }

    @Test
    void boundsHttpResponseAndDoesNotExposeProviderErrors() throws Exception {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> VisualAiClient.responseProfile(
                        401,
                        new ByteArrayInputStream(
                                "provider response containing secret".getBytes(StandardCharsets.UTF_8))));
        assertFalse(error.getMessage().contains("secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> VisualAiClient.responseProfile(200, new ByteArrayInputStream(new byte[262145])));
        var subscriber = new BoundedAiResponse();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[262145])));
        verify(subscription).cancel();
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
