package com.emie.designpm.assistant;

import com.emie.designpm.assistant.repository.AssistantModelProfileRepository;
import com.emie.designpm.assistant.repository.AssistantModelSelectionRepository;
import com.emie.designpm.entity.AssistantModelProfile;
import com.emie.designpm.entity.AssistantModelSelection;
import com.emie.designpm.recommendation.service.VisualAiSettings;
import com.emie.designpm.recommendation.service.VisualAiSettings.RuntimeConfig;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Global administrator-managed profiles. Server credentials never become custom profile defaults. */
@Service
public class AssistantModelSettings {
    private static final String SERVER = "server";
    private static final Set<String> PROVIDERS = Set.of("kimi", "deepseek", "qwen", "openai", "custom");
    private static final Set<String> DIALECTS = Set.of("kimi", "deepseek", "qwen", "openai", "compatible");
    private static final Set<String> THINKING = Set.of("auto", "disabled", "enabled");
    private final AssistantModelProfileRepository profiles;
    private final AssistantModelSelectionRepository selections;
    private final VisualAiSettings server;

    public AssistantModelSettings(
            AssistantModelProfileRepository profiles,
            AssistantModelSelectionRepository selections,
            VisualAiSettings server) {
        this.profiles = profiles;
        this.selections = selections;
        this.server = server;
    }

    @Transactional(readOnly = true)
    public RuntimeConfig current() {
        RuntimeConfig builtin = server.serverCurrent();
        AssistantModelSelection selection = selections.findById(1L).orElse(null);
        boolean enabled =
                selection == null || selection.getEnabled() == null ? builtin.enabled : selection.getEnabled();
        String active = selection == null ? SERVER : selection.getActiveProfileId();
        if (!SERVER.equals(active)) {
            AssistantModelProfile profile = profiles.findById(active).orElse(null);
            if (profile != null) return runtime(profile, enabled);
        }
        return new RuntimeConfig(
                builtin.baseUrl,
                builtin.model,
                builtin.apiKey,
                enabled,
                "kimi",
                "kimi",
                "服务器 Kimi（默认）",
                true,
                SERVER,
                builtin.thinkingMode);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicModel() {
        RuntimeConfig active = current();
        return Map.of(
                "modelName",
                active.displayName,
                "modelId",
                active.profileId,
                "provider",
                active.provider,
                "visionSupported",
                active.visionSupported,
                "enabled",
                active.enabled,
                "activeProfileId",
                active.profileId,
                "displayName",
                active.displayName,
                "model",
                active.model);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        RuntimeConfig active = current();
        RuntimeConfig builtin = server.serverCurrent();
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(view(builtin, SERVER.equals(active.profileId), true));
        for (AssistantModelProfile profile : profiles.findAllByOrderByUpdatedAtDesc()) {
            list.add(view(runtime(profile, active.enabled), profile.getId().equals(active.profileId), false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", active.enabled);
        result.put("baseUrl", active.baseUrl);
        result.put("model", active.model);
        result.put("keyConfigured", !active.apiKey.isBlank());
        result.put("keyStorageAvailable", server.keyStorageAvailable());
        result.put("activeProfileId", active.profileId);
        result.put("profiles", list);
        result.put("modelName", active.displayName);
        result.put("modelId", active.profileId);
        result.put("provider", active.provider);
        result.put("visionSupported", active.visionSupported);
        result.put(
                "message",
                !server.keyStorageAvailable()
                        ? "服务器加密配置不足，暂不能保存新的模型密钥"
                        : active.apiKey.isBlank() ? "当前模型没有可用密钥，请检查服务器配置或重新保存该模型的密钥" : "");
        return result;
    }

    @Transactional
    public synchronized Map<String, Object> save(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("模型设置格式无效");
        String operation = string(body.get("operation"));
        AssistantModelSelection selection = lockedSelection();
        String saved = null;
        switch (operation) {
            case "save" -> {
                if (!(body.get("profile") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("请输入模型配置");
                saved = saveProfile(raw);
                if (Boolean.TRUE.equals(body.get("activate"))) {
                    requireReadableKey(profiles.findById(saved).orElseThrow());
                    selection.setActiveProfileId(saved);
                }
            }
            case "activate" -> {
                String id = profileId(body.get("profileId"));
                if (!SERVER.equals(id)) {
                    AssistantModelProfile profile =
                            profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("模型配置不存在，请刷新列表"));
                    requireReadableKey(profile);
                }
                selection.setActiveProfileId(id);
            }
            case "delete" -> {
                String id = profileId(body.get("profileId"));
                if (SERVER.equals(id)) throw new IllegalArgumentException("服务器默认模型不可删除");
                AssistantModelProfile profile =
                        profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("模型配置不存在，请刷新列表"));
                if (id.equals(selection.getActiveProfileId())) selection.setActiveProfileId(SERVER);
                profiles.delete(profile);
            }
            case "setEnabled" -> {
                if (!(body.get("enabled") instanceof Boolean enabled))
                    throw new IllegalArgumentException("请设置是否启用小觅模型");
                selection.setEnabled(enabled);
            }
            default -> throw new IllegalArgumentException("请选择保存、切换或删除模型配置");
        }
        selections.saveAndFlush(selection);
        Map<String, Object> result = new LinkedHashMap<>(overview());
        if (saved != null) result.put("savedProfileId", saved);
        return result;
    }

    private String saveProfile(Map<?, ?> body) {
        String id = string(body.get("id"));
        if (SERVER.equals(id)) throw new IllegalArgumentException("服务器默认模型由部署配置管理，请添加独立配置");
        AssistantModelProfile profile;
        boolean fresh = id.isBlank();
        if (fresh) {
            if (profiles.count() >= 10) throw new IllegalArgumentException("最多保存10套自定义模型配置");
            profile = new AssistantModelProfile();
            profile.setId(UUID.randomUUID().toString());
        } else {
            profile = profiles.findById(profileId(id)).orElseThrow(() -> new IllegalArgumentException("模型配置不存在，请刷新列表"));
        }
        String name = validText(body.get("name"), 100, "请输入100字以内的配置名称");
        String provider = validText(body.get("provider"), 30, "请选择服务商");
        String dialect = validText(body.get("dialect"), 30, "请选择接口格式");
        if (!PROVIDERS.contains(provider) || !DIALECTS.contains(dialect))
            throw new IllegalArgumentException("服务商或接口格式无效");
        String base = validText(body.get("baseUrl"), 500, "请输入有效的接口地址").replaceAll("/+$", "");
        VisualAiSettings.validateBaseUrl(base);
        String model = validText(body.get("model"), 150, "请输入150字以内的模型名称");
        String thinking = string(body.get("thinkingMode"));
        if (thinking.isBlank()) thinking = "auto";
        if (!THINKING.contains(thinking)
                || Set.of("openai", "compatible").contains(dialect) && !"auto".equals(thinking))
            throw new IllegalArgumentException("当前接口格式不支持所选思考设置");
        if (!(body.get("visionSupported") instanceof Boolean vision))
            throw new IllegalArgumentException("请设置模型是否支持图片理解");
        String key = string(body.get("apiKey"));
        if (key.length() > 4000 || key.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("模型密钥格式无效");
        if (key.isBlank()
                && (fresh
                        || !Objects.equals(base, profile.getBaseUrl())
                        || !Objects.equals(provider, profile.getProvider())))
            throw new IllegalArgumentException("新模型或更换服务商/接口地址时必须填写对应密钥");
        if (!key.isBlank()) profile.setEncryptedKey(server.encrypt(key));
        if (profile.getEncryptedKey() == null || profile.getEncryptedKey().isBlank())
            throw new IllegalArgumentException("请为该模型填写密钥");
        profile.setDisplayName(name);
        profile.setProvider(provider);
        profile.setDialect(dialect);
        profile.setThinkingMode(thinking);
        profile.setBaseUrl(base);
        profile.setModel(model);
        profile.setVisionSupported(vision);
        profile.setUpdatedAt(LocalDateTime.now());
        profiles.saveAndFlush(profile);
        return profile.getId();
    }

    private AssistantModelSelection lockedSelection() {
        AssistantModelSelection result = selections.findForUpdate().orElse(null);
        if (result != null) return result;
        // Migration seeds the singleton; retain a safe fallback for a deliberately emptied development DB.
        result = new AssistantModelSelection();
        result.setId(1L);
        result.setActiveProfileId(SERVER);
        return selections.saveAndFlush(result);
    }

    private RuntimeConfig runtime(AssistantModelProfile profile, boolean enabled) {
        String key;
        try {
            key = server.decrypt(profile.getEncryptedKey());
        } catch (IllegalArgumentException unusable) {
            key = "";
        }
        return new RuntimeConfig(
                profile.getBaseUrl(),
                profile.getModel(),
                key,
                enabled,
                profile.getProvider(),
                profile.getDialect(),
                profile.getDisplayName(),
                profile.isVisionSupported(),
                profile.getId(),
                profile.getThinkingMode());
    }

    private void requireReadableKey(AssistantModelProfile profile) {
        if (runtime(profile, true).apiKey.isBlank()) throw new IllegalArgumentException("该模型密钥不可用，请重新填写并保存密钥");
    }

    private Map<String, Object> view(RuntimeConfig config, boolean active, boolean builtin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", config.profileId);
        result.put("name", config.displayName);
        result.put("provider", config.provider);
        result.put("dialect", config.dialect);
        result.put("thinkingMode", config.thinkingMode);
        result.put("baseUrl", config.baseUrl);
        result.put("model", config.model);
        result.put("visionSupported", config.visionSupported);
        result.put("keyConfigured", !config.apiKey.isBlank());
        result.put("builtin", builtin);
        result.put("active", active);
        return result;
    }

    private static String profileId(Object value) {
        String id = string(value);
        if (!id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("模型配置标识无效");
        return id;
    }

    private static String validText(Object value, int limit, String message) {
        String text = string(value);
        if (text.isBlank() || text.length() > limit || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(message);
        return text;
    }

    private static String string(Object value) {
        return value instanceof String text ? text.trim() : "";
    }
}
