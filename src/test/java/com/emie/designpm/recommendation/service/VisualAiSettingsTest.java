package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.VisualAiConfig;
import com.emie.designpm.recommendation.repository.VisualAiConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VisualAiSettingsTest {
    @Test
    void encryptsStoredKeyAndNeverReturnsItInOverview() {
        var repository = mock(VisualAiConfigRepository.class);
        var settings = new VisualAiSettings(
                repository,
                "",
                "https://api.moonshot.cn/v1",
                "kimi-k2.6",
                "local-test-encryption-secret-32-characters",
                true);
        String encrypted = ReflectionTestUtils.invokeMethod(settings, "encrypt", "private-test-key");
        assertNotNull(encrypted);
        assertFalse(encrypted.contains("private-test-key"));
        VisualAiConfig row = new VisualAiConfig();
        row.setId(1L);
        row.setBaseUrl("https://api.moonshot.cn/v1");
        row.setModel("kimi-k2.6");
        row.setEnabled(true);
        row.setEncryptedKey(encrypted);
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        assertEquals("private-test-key", settings.current().apiKey);
        assertEquals(true, settings.overview().get("keyConfigured"));
        assertFalse(settings.overview().toString().contains("private-test-key"));
        var wrongSecret = new VisualAiSettings(
                repository,
                "",
                "https://api.moonshot.cn/v1",
                "kimi-k2.6",
                "different-test-encryption-secret-32-characters",
                true);
        assertFalse((boolean) wrongSecret.overview().get("keyConfigured"));
        assertTrue(wrongSecret.overview().get("message").toString().contains("解密"));
    }

    @Test
    void refusesPrivateEndpointsAndUnencryptedKeyPersistence() {
        assertThrows(
                IllegalArgumentException.class, () -> VisualAiSettings.validateBaseUrl("http://api.moonshot.cn/v1"));
        assertThrows(IllegalArgumentException.class, () -> VisualAiSettings.validateBaseUrl("https://127.0.0.1/v1"));
        assertThrows(IllegalArgumentException.class, () -> VisualAiSettings.validateBaseUrl("https://10.0.0.1/v1"));
        assertThrows(
                IllegalArgumentException.class, () -> VisualAiSettings.validateBaseUrl("https://100.100.100.200/v1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> VisualAiSettings.validateBaseUrl("https://user:pass@api.moonshot.cn/v1"));
        var settings = new VisualAiSettings(
                mock(VisualAiConfigRepository.class), "", "https://api.moonshot.cn/v1", "kimi-k2.6", "", true);
        assertEquals(false, settings.overview().get("keyStorageAvailable"));
        assertThrows(
                IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(settings, "encrypt", "test"));
    }
}
