package com.emie.designpm.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.assistant.repository.AssistantModelProfileRepository;
import com.emie.designpm.assistant.repository.AssistantModelSelectionRepository;
import com.emie.designpm.entity.AssistantModelProfile;
import com.emie.designpm.entity.AssistantModelSelection;
import com.emie.designpm.entity.VisualAiConfig;
import com.emie.designpm.recommendation.repository.VisualAiConfigRepository;
import com.emie.designpm.recommendation.service.VisualAiSettings;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantModelSettingsTest {
    private final AssistantModelProfileRepository profiles = mock(AssistantModelProfileRepository.class);
    private final AssistantModelSelectionRepository selections = mock(AssistantModelSelectionRepository.class);
    private final VisualAiConfigRepository legacy = mock(VisualAiConfigRepository.class);
    private final Map<String, AssistantModelProfile> stored = new LinkedHashMap<>();
    private final AtomicReference<AssistantModelSelection> selection = new AtomicReference<>();
    private final VisualAiSettings server = new VisualAiSettings(
            legacy,
            "server-only-secret",
            "https://api.moonshot.cn/v1",
            "kimi-k2.6",
            "local-test-encryption-secret-32-characters",
            true);
    private final AssistantModelSettings settings =
            new AssistantModelSettings(profiles, selections, server, new AssistantEndpointPolicy(""));

    @BeforeEach
    void setup() {
        AssistantModelSelection row = new AssistantModelSelection();
        row.setId(1L);
        row.setActiveProfileId("server");
        selection.set(row);
        when(legacy.findById(1L)).thenReturn(Optional.empty());
        when(profiles.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(profiles.findAllByOrderByUpdatedAtDesc()).thenAnswer(call -> new ArrayList<>(stored.values()));
        when(profiles.count()).thenAnswer(call -> (long) stored.size());
        when(profiles.saveAndFlush(any())).thenAnswer(call -> {
            AssistantModelProfile value = call.getArgument(0);
            stored.put(value.getId(), value);
            return value;
        });
        doAnswer(call -> {
                    stored.remove(((AssistantModelProfile) call.getArgument(0)).getId());
                    return null;
                })
                .when(profiles)
                .delete(any(AssistantModelProfile.class));
        when(selections.findById(1L)).thenAnswer(call -> Optional.of(selection.get()));
        when(selections.findForUpdate()).thenAnswer(call -> Optional.of(selection.get()));
        when(selections.saveAndFlush(any())).thenAnswer(call -> {
            selection.set(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    private Map<String, Object> profile(String key) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "另一套模型");
        value.put("provider", "custom");
        value.put("dialect", "compatible");
        value.put("baseUrl", "https://1.1.1.1/v1");
        value.put("model", "custom-chat");
        value.put("visionSupported", false);
        value.put("thinkingMode", "auto");
        if (key != null) value.put("apiKey", key);
        return value;
    }

    private String save(Map<String, Object> value, boolean activate) {
        return (String) settings.save(Map.of("operation", "save", "profile", value, "activate", activate))
                .get("savedProfileId");
    }

    @Test
    void defaultsToProtectedServerAndPublicMetadataNeverContainsCredentials() {
        var runtime = settings.current();
        assertEquals("server", runtime.profileId);
        assertEquals("server-only-secret", runtime.apiKey);
        assertEquals("https://api.moonshot.cn/v1", runtime.baseUrl);
        assertEquals("disabled", runtime.thinkingMode);
        assertEquals("server", settings.overview().get("activeProfileId"));
        assertFalse(settings.overview().toString().contains("server-only-secret"));
        assertFalse(settings.publicModel().containsKey("baseUrl"));
        assertFalse(settings.publicModel().toString().contains("server-only-secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings.save(Map.of("operation", "delete", "profileId", "server")));
        Map<String, Object> overwrite = profile("own-key");
        overwrite.put("id", "server");
        assertThrows(IllegalArgumentException.class, () -> save(overwrite, true));
    }

    @Test
    void newProfileNeedsItsOwnKeyAndStoresOnlyCiphertext() {
        assertThrows(IllegalArgumentException.class, () -> save(profile(null), true));
        String id = save(profile("custom-only-secret"), true);
        AssistantModelProfile row = stored.get(id);
        assertNotEquals("custom-only-secret", row.getEncryptedKey());
        assertFalse(row.getEncryptedKey().contains("custom-only-secret"));
        assertEquals("custom-only-secret", settings.current().apiKey);
        assertEquals("compatible", settings.current().dialect);
        assertFalse(settings.current().visionSupported);
        assertFalse(settings.overview().toString().contains("custom-only-secret"));
        assertFalse(settings.overview().toString().contains(row.getEncryptedKey()));
        assertFalse(settings.overview().toString().contains("server-only-secret"));
    }

    @Test
    void savesWithoutActivatingAndSelectionSurvivesServiceRecreation() {
        String id = save(profile("own-key"), false);
        assertEquals("server", settings.current().profileId);
        settings.save(Map.of("operation", "activate", "profileId", id));
        var restarted = new AssistantModelSettings(profiles, selections, server, new AssistantEndpointPolicy(""));
        assertEquals(id, restarted.current().profileId);
        assertEquals("own-key", restarted.current().apiKey);
        assertEquals("另一套模型", restarted.publicModel().get("modelName"));
        restarted.save(Map.of("operation", "activate", "profileId", "server"));
        assertEquals("server-only-secret", settings.current().apiKey);
        verify(selections, atLeastOnce()).findForUpdate();
    }

    @Test
    void trustedLanProfileNeedsItsOwnKeyAndPersistsAcrossRestart() {
        String base = "http://192.168.200.118:8317/v1";
        var policy = new AssistantEndpointPolicy(base);
        var lanSettings = new AssistantModelSettings(profiles, selections, server, policy);
        Map<String, Object> value = profile(null);
        value.put("baseUrl", base);
        value.put("model", "lan-chat");
        assertThrows(
                IllegalArgumentException.class,
                () -> lanSettings.save(Map.of("operation", "save", "profile", value, "activate", true)));
        assertEquals("server", lanSettings.current().profileId);
        verify(profiles, never()).saveAndFlush(any());

        value.put("apiKey", "lan-profile-only-secret");
        String id = (String) lanSettings
                .save(Map.of("operation", "save", "profile", value, "activate", true))
                .get("savedProfileId");
        assertEquals(base, stored.get(id).getBaseUrl());
        assertFalse(stored.get(id).getEncryptedKey().contains("lan-profile-only-secret"));
        var restarted = new AssistantModelSettings(profiles, selections, server, policy);
        assertEquals(id, restarted.current().profileId);
        assertEquals(base, restarted.current().baseUrl);
        assertEquals("lan-chat", restarted.current().model);
        assertEquals("lan-profile-only-secret", restarted.current().apiKey);
        assertFalse(restarted.overview().toString().contains("lan-profile-only-secret"));
        assertFalse(restarted.overview().toString().contains("server-only-secret"));

        Map<String, Object> changed = profile("");
        changed.put("id", id);
        assertThrows(
                IllegalArgumentException.class, () -> restarted.save(Map.of("operation", "save", "profile", changed)));
        assertEquals(base, restarted.current().baseUrl);
        stored.get(id).setEncryptedKey("unreadable-lan-key");
        assertEquals("", restarted.current().apiKey);
        assertFalse(restarted.current().configured());
        assertEquals("server-only-secret", server.serverCurrent().apiKey);
    }

    @Test
    void blankKeyOnlyRetainsSameProfileAtSameProviderAndEndpoint() {
        String id = save(profile("private-old-key"), true);
        String ciphertext = stored.get(id).getEncryptedKey();
        Map<String, Object> edited = profile("");
        edited.put("id", id);
        edited.put("model", "another-model");
        save(edited, false);
        assertEquals("private-old-key", settings.current().apiKey);
        assertEquals(ciphertext, stored.get(id).getEncryptedKey());
        edited.put("baseUrl", "https://8.8.8.8/v1");
        assertThrows(IllegalArgumentException.class, () -> save(edited, false));
        assertEquals("https://1.1.1.1/v1", settings.current().baseUrl);
        edited.put("baseUrl", "https://1.1.1.1/v1");
        edited.put("provider", "deepseek");
        assertThrows(IllegalArgumentException.class, () -> save(edited, false));
        edited.put("apiKey", "replacement-key");
        save(edited, false);
        assertEquals("replacement-key", settings.current().apiKey);
    }

    @Test
    void deletingActiveProfileAtomicallyReturnsToServerAndPreservesEnableSwitch() {
        String id = save(profile("own-key"), true);
        settings.save(Map.of("operation", "setEnabled", "enabled", false));
        settings.save(Map.of("operation", "delete", "profileId", id));
        assertEquals("server", selection.get().getActiveProfileId());
        assertEquals("server", settings.current().profileId);
        assertFalse(settings.current().enabled);
        assertTrue(stored.isEmpty());
    }

    @Test
    void validatesLimitsAndDoesNotGuessVisionOrThinkingFromModelName() {
        Map<String, Object> value = profile("key");
        value.put("thinkingMode", "disabled");
        assertThrows(IllegalArgumentException.class, () -> save(value, true));
        value.put("dialect", "qwen");
        value.put("thinkingMode", "enabled");
        String id = save(value, true);
        assertEquals("enabled", settings.current().thinkingMode);
        assertFalse(settings.current().visionSupported);
        value.put("id", id);
        value.put("baseUrl", "https://127.0.0.1/v1");
        assertThrows(IllegalArgumentException.class, () -> save(value, false));
        for (int i = 0; i < 9; i++) save(profile("key-" + i), false);
        assertThrows(IllegalArgumentException.class, () -> save(profile("overflow"), false));
    }

    @Test
    void legacyForeignEndpointCannotInheritServerKeyAndBuiltinStaysBoundToServer() {
        VisualAiConfig old = new VisualAiConfig();
        old.setId(1L);
        old.setEnabled(true);
        old.setBaseUrl("https://8.8.8.8/v1");
        old.setModel("foreign-chat");
        when(legacy.findById(1L)).thenReturn(Optional.of(old));
        assertFalse(server.current().configured());
        assertEquals("https://api.moonshot.cn/v1", settings.current().baseUrl);
        assertEquals("server-only-secret", settings.current().apiKey);
        old.setBaseUrl("https://api.moonshot.cn/v1");
        old.setEncryptedKey(server.encrypt("saved-kimi-key"));
        assertEquals("saved-kimi-key", settings.current().apiKey);
        assertEquals("kimi-k2.6", settings.current().model);
    }

    @Test
    void decryptionFailureNeverFallsBackToAnotherProfileOrServerSecret() {
        String id = save(profile("custom-secret"), true);
        stored.get(id).setEncryptedKey("bad-ciphertext");
        assertEquals(id, settings.current().profileId);
        assertFalse(settings.current().configured());
        assertEquals("", settings.current().apiKey);
        assertFalse((boolean) settings.overview().get("keyConfigured"));
        settings.save(Map.of("operation", "activate", "profileId", "server"));
        assertThrows(
                IllegalArgumentException.class, () -> settings.save(Map.of("operation", "activate", "profileId", id)));
        assertEquals("server", settings.current().profileId);
    }

    @Test
    void unreadableLegacyServerKeyDoesNotBlockConfiguredCustomProfile() {
        String id = save(profile("custom-secret"), true);
        VisualAiConfig old = new VisualAiConfig();
        old.setId(1L);
        old.setEnabled(true);
        old.setBaseUrl("https://api.moonshot.cn/v1");
        old.setModel("kimi-k2.6");
        old.setEncryptedKey("unreadable-legacy-ciphertext");
        when(legacy.findById(1L)).thenReturn(Optional.of(old));
        assertEquals(id, settings.current().profileId);
        assertEquals("custom-secret", settings.current().apiKey);
        assertFalse(server.serverCurrent().configured());
    }
}
