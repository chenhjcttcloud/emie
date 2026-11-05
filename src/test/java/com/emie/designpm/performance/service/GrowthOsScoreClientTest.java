package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrowthOsScoreClientTest {
    private static final String TOKEN = "fixture-growth-score-client-token-32-characters";
    private static final String PATH = "/api/integrations/product-management/monthly-scores";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(int status, String response, AtomicReference<String> auth, AtomicReference<String> body)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            if (auth != null) auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (body != null) body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private GrowthOsScoreClient client(String base) {
        return new GrowthOsScoreClient(
                base,
                TOKEN,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Duration.ofSeconds(2));
    }

    @Test
    void sendsOnlyExplicitBackendTokenAndBoundedIdentityPayload() throws Exception {
        var auth = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var client =
                client(serve(200, "{\"ok\":true,\"apiVersion\":\"1\",\"month\":\"2026-10\",\"data\":[]}", auth, body));
        var result = client.fetch("2026-10", List.of(new GrowthOsScoreClient.Identity("feishu-fixture", "测试同名")));
        assertTrue(result.path("ok").booleanValue());
        assertEquals("Bearer " + TOKEN, auth.get());
        var payload = new ObjectMapper().readTree(body.get());
        assertEquals("2026-10", payload.path("month").textValue());
        assertEquals(
                "feishu-fixture",
                payload.path("employees").get(0).path("feishuUserId").textValue());
        assertEquals("测试同名", payload.path("employees").get(0).path("name").textValue());
        assertFalse(body.get().contains(TOKEN));
    }

    @Test
    void absentConfigurationIsDisabledButInvalidConfigurationCannotBecomeManualMode() {
        assertFalse(new GrowthOsScoreClient("", "", false).isConfigured());
        assertFalse(new GrowthOsScoreClient("https://growth.example", "", false).isConfigured());
        for (String base : List.of(
                "http://example.com",
                "https://secret@example.com",
                "https://example.com/?token=secret",
                "https://example.com/#fragment",
                "https://example.com/subpath",
                "http://growth-os:5174")) {
            var client = client(base);
            assertTrue(client.isConfigured());
            var failure = assertThrows(
                    GrowthOsScoreClient.Unavailable.class,
                    () -> client.fetch("2026-10", List.of(new GrowthOsScoreClient.Identity("id", "测试"))));
            assertFalse(failure.getMessage().contains("secret"));
            assertFalse(failure.getMessage().contains(TOKEN));
        }
        var invalidToken = new GrowthOsScoreClient("https://growth.example", "invalid token", false);
        assertTrue(invalidToken.isConfigured());
        assertThrows(
                GrowthOsScoreClient.Unavailable.class,
                () -> invalidToken.fetch("2026-10", List.of(new GrowthOsScoreClient.Identity("id", "测试"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restrictsInternalHttpAliasToExplicitDevelopmentFlag() {
        var transport = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn("{\"ok\":true,\"apiVersion\":\"1\",\"month\":\"2026-10\",\"data\":[]}"
                        .getBytes(StandardCharsets.UTF_8));
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        var identities = List.of(new GrowthOsScoreClient.Identity("id", "测试"));
        var allowed = new GrowthOsScoreClient("http://growth-os:5174", TOKEN, transport, Duration.ofSeconds(2), true);
        allowed.fetch("2026-10", identities);
        verify(transport)
                .sendAsync(argThat(request -> request.uri().toString().equals("http://growth-os:5174" + PATH)), any());
        clearInvocations(transport);
        for (var forbidden : List.of(
                new GrowthOsScoreClient("http://growth-os:5174", TOKEN, transport, Duration.ofSeconds(2), false),
                new GrowthOsScoreClient(
                        "http://some-other-service:5174", TOKEN, transport, Duration.ofSeconds(2), true))) {
            assertTrue(forbidden.isConfigured());
            assertThrows(GrowthOsScoreClient.Unavailable.class, () -> forbidden.fetch("2026-10", identities));
        }
        verifyNoInteractions(transport);
    }

    @Test
    void upstreamFailuresAndMalformedDataNeverExposeResponseOrCredentials() throws Exception {
        for (int status : List.of(401, 403, 429, 503, 302)) {
            String base = serve(status, "private upstream content " + TOKEN, null, null);
            var failure = assertThrows(GrowthOsScoreClient.Unavailable.class, () -> client(base)
                    .fetch("2026-10", List.of(new GrowthOsScoreClient.Identity("id", "测试"))));
            assertFalse(failure.getMessage().contains(TOKEN));
            assertFalse(failure.getMessage().contains("private upstream"));
            server.stop(0);
            server = null;
        }
        String base = serve(200, "{\"ok\":true,\"apiVersion\":\"2\",\"month\":\"2026-10\",\"data\":[]}", null, null);
        assertThrows(GrowthOsScoreClient.Unavailable.class, () -> client(base)
                .fetch("2026-10", List.of(new GrowthOsScoreClient.Identity("id", "测试"))));
    }

    @Test
    void rejectsOversizedResponsesAndTimesOutWithoutRetry() throws Exception {
        String base = serve(200, "x".repeat(GrowthOsScoreClient.MAX_RESPONSE_BYTES + 1), null, null);
        assertThrows(GrowthOsScoreClient.Unavailable.class, () -> client(base)
                .fetch("2026-10", List.of(new GrowthOsScoreClient.Identity("id", "测试"))));
        server.stop(0);
        var calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        var timed = new GrowthOsScoreClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                TOKEN,
                HttpClient.newHttpClient(),
                Duration.ofMillis(100));
        assertThrows(
                GrowthOsScoreClient.Unavailable.class,
                () -> timed.fetch("2026-10", List.of(new GrowthOsScoreClient.Identity("id", "测试"))));
        assertEquals(1, calls.get());
    }
}
