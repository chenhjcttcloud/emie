package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.VisualAnalysisCache;
import com.emie.designpm.recommendation.analysis.VisualProfile;
import com.emie.designpm.recommendation.repository.VisualAnalysisCacheRepository;
import com.emie.designpm.recommendation.repository.VisualSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VisualAnalysisServiceTest {
    private final VisualAiSettings settings = mock(VisualAiSettings.class);
    private final VisualAiClient client = mock(VisualAiClient.class);
    private final VisualInputReader input = mock(VisualInputReader.class);
    private final VisualAnalysisCacheRepository cache = mock(VisualAnalysisCacheRepository.class);
    private final VisualSourceRepository sources = mock(VisualSourceRepository.class);
    private final VisualAnalysisService service = new VisualAnalysisService(settings, client, input, cache, sources);

    @AfterEach
    void close() {
        service.close();
    }

    private void configured() throws Exception {
        when(settings.current())
                .thenReturn(new VisualAiSettings.RuntimeConfig(
                        "https://api.moonshot.cn/v1", "kimi-k2.6", "mock-key", true));
        FileRecord record =
                FileRecord.builder().storedName("cover.png").fileSize(100L).build();
        when(sources.findByStoredNameIn(any())).thenReturn(List.of(record));
        when(input.supported("cover.png")).thenReturn(true);
        when(input.metadataFingerprint(eq(record), anyString())).thenReturn("fingerprint");
        when(input.fingerprint(eq(record), anyString())).thenReturn("content");
        when(input.imageData(record)).thenReturn(List.of("data:image/jpeg;base64,YWJj"));
    }

    @Test
    void missingConfigurationUsesMetadataWithoutFileOrModelAccess() {
        when(settings.current())
                .thenReturn(new VisualAiSettings.RuntimeConfig("https://api.moonshot.cn/v1", "kimi-k2.6", "", true));
        assertEquals("UNCONFIGURED", service.profile("cover.png").status());
        verifyNoInteractions(client, sources, input, cache);
    }

    @Test
    void batchCacheReadsNeverDecodeRestoreOrAnalyzeFiles() throws Exception {
        configured();
        VisualAnalysisCache row = row("READY");
        row.setProfileJson(new ObjectMapper()
                .writeValueAsString(
                        new VisualProfile("蓝色包装", List.of("海洋"), List.of("鲸鱼"), List.of(), List.of("蓝色"), List.of())));
        when(cache.findAllById(any())).thenReturn(List.of(row));
        assertEquals(
                "READY", service.peekAll(List.of("cover.png")).get("cover.png").status());
        verify(input, never()).imageData(any());
        verify(input, never()).fingerprint(any(), anyString());
        verifyNoInteractions(client);
        when(input.metadataFingerprint(any(), anyString())).thenReturn("new-fingerprint");
        assertEquals("PENDING", service.peek("cover.png").status());
        verifyNoInteractions(client);
    }

    @Test
    void repeatedAccessDeduplicatesInFlightAnalysisAndPersistsProfile() throws Exception {
        configured();
        CountDownLatch start = new CountDownLatch(1), release = new CountDownLatch(1), saved = new CountDownLatch(1);
        when(cache.findAllById(any())).thenReturn(List.of());
        when(cache.findById(anyString())).thenReturn(Optional.empty());
        when(client.analyze(any(), any())).thenAnswer(invocation -> {
            start.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new VisualProfile("鲸鱼礼盒", List.of("海洋"), List.of("鲸鱼"), List.of(), List.of(), List.of());
        });
        when(cache.save(any())).thenAnswer(invocation -> {
            saved.countDown();
            return invocation.getArgument(0);
        });
        assertEquals("PENDING", service.profile("cover.png").status());
        assertTrue(start.await(3, TimeUnit.SECONDS));
        service.profile("cover.png");
        release.countDown();
        assertTrue(saved.await(3, TimeUnit.SECONDS));
        verify(client, times(1)).analyze(any(), any());
        verify(cache)
                .save(argThat(row ->
                        "READY".equals(row.getStatus()) && row.getProfileJson().contains("鲸鱼")));
    }

    @Test
    void failuresRespectCooldownAndRetryLimit() throws Exception {
        configured();
        VisualAnalysisCache row = row("FAILED");
        row.setAttempts(1);
        row.setRetryAfter(LocalDateTime.now().plusMinutes(5));
        when(cache.findAllById(any())).thenReturn(List.of(row));
        assertEquals("FAILED", service.profile("cover.png").status());
        row.setRetryAfter(LocalDateTime.now().minusMinutes(1));
        row.setAttempts(3);
        assertEquals("FAILED", service.profile("cover.png").status());
        verifyNoInteractions(client);
    }

    @Test
    void indexesFilesBeyondTwoThousandAndSeparatesUnsupportedFiles() throws Exception {
        configured();
        Map<String, FileRecord> records = new LinkedHashMap<>();
        Map<String, VisualAnalysisCache> rows = new HashMap<>();
        String profile = new ObjectMapper()
                .writeValueAsString(new VisualProfile("礼盒", List.of(), List.of(), List.of(), List.of(), List.of()));
        for (int i = 0; i < 2004; i++) {
            String name = "file-" + i + ".png";
            records.put(
                    name, FileRecord.builder().storedName(name).fileSize(100L).build());
            VisualAnalysisCache row = row("READY");
            row.setStoredName(name);
            row.setProfileJson(profile);
            rows.put(name, row);
        }
        records.put(
                "last.png",
                FileRecord.builder().storedName("last.png").fileSize(100L).build());
        records.put(
                "unsupported.psd",
                FileRecord.builder()
                        .storedName("unsupported.psd")
                        .fileSize(100L)
                        .build());
        when(sources.findByStoredNameIn(any()))
                .thenAnswer(invocation -> ((Collection<String>) invocation.getArgument(0))
                        .stream().map(records::get).toList());
        when(sources.findByTargetTypeOrderByCreatedAtDesc("image_library"))
                .thenReturn(new ArrayList<>(records.values()));
        when(cache.findAllById(any())).thenAnswer(invocation -> ((Collection<String>) invocation.getArgument(0))
                .stream().map(rows::get).filter(Objects::nonNull).toList());
        when(input.supported(anyString())).thenReturn(true);
        when(input.supported("unsupported.psd")).thenReturn(false);
        when(input.metadataFingerprint(any(), anyString())).thenReturn("fingerprint");
        when(input.fingerprint(any(), anyString())).thenReturn("content");
        when(input.imageData(any())).thenReturn(List.of("data:image/jpeg;base64,YWJj"));
        when(client.analyze(any(), any()))
                .thenReturn(new VisualProfile("礼盒", List.of(), List.of(), List.of(), List.of(), List.of()));
        assertEquals(2006, service.peekAll(records.keySet()).size());
        assertEquals(1L, service.indexStats().get("unsupported"));
        assertEquals(1L, service.indexStats().get("pending"));
        assertEquals(1, service.indexLibrary().get("queued"));
        verify(client, timeout(3000)).analyze(any(), any());
    }

    private VisualAnalysisCache row(String status) {
        VisualAnalysisCache row = new VisualAnalysisCache();
        row.setStoredName("cover.png");
        row.setFingerprint("fingerprint");
        row.setStatus(status);
        row.setUpdatedAt(LocalDateTime.now());
        row.setMessage("fallback");
        return row;
    }

    @Test
    void removedSourcesAreNotSentToProviderAfterWaitingInQueue() throws Exception {
        configured();
        FileRecord record =
                FileRecord.builder().storedName("cover.png").fileSize(100L).build();
        CountDownLatch rechecked = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(sources.findByStoredNameIn(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) return List.of(record);
            rechecked.countDown();
            return List.of();
        });
        when(input.metadataFingerprint(any(), anyString())).thenReturn("fingerprint");
        assertEquals("PENDING", service.profile("cover.png").status());
        assertTrue(rechecked.await(3, TimeUnit.SECONDS));
        verifyNoInteractions(client);
        verify(cache, never()).save(any());
    }
}
