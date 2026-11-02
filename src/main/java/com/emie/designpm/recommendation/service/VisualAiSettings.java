package com.emie.designpm.recommendation.service;

import com.emie.designpm.entity.VisualAiConfig;
import com.emie.designpm.recommendation.repository.VisualAiConfigRepository;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VisualAiSettings {
    private final VisualAiConfigRepository repository;
    private final String environmentKey;
    private final String environmentBase;
    private final String environmentModel;
    private final String encryptionSecret;
    private final boolean environmentEnabled;

    /** Do not add a generated toString: this record contains credentials. */
    public static final class RuntimeConfig {
        public final String baseUrl;
        public final String model;
        public final String apiKey;
        public final boolean enabled;

        RuntimeConfig(String baseUrl, String model, String apiKey, boolean enabled) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey;
            this.enabled = enabled;
        }

        public boolean configured() {
            return enabled && !apiKey.isBlank();
        }

        public String fingerprint() {
            return digest("visual-profile-v1|" + baseUrl + "|" + model + "|" + digest(apiKey));
        }
    }

    public VisualAiSettings(
            VisualAiConfigRepository repository,
            @Value("${VISUAL_AI_API_KEY:}") String environmentKey,
            @Value("${VISUAL_AI_BASE_URL:https://api.moonshot.cn/v1}") String environmentBase,
            @Value("${VISUAL_AI_MODEL:kimi-k2.6}") String environmentModel,
            @Value("${VISUAL_AI_ENCRYPTION_KEY:}") String encryptionSecret,
            @Value("${VISUAL_AI_ENABLED:false}") boolean enabled) {
        this.repository = repository;
        this.environmentKey = environmentKey.trim();
        this.environmentBase = environmentBase.replaceAll("/+$", "");
        this.environmentModel = environmentModel;
        this.encryptionSecret = encryptionSecret;
        this.environmentEnabled = enabled;
    }

    public RuntimeConfig current() {
        VisualAiConfig stored = repository.findById(1L).orElse(null);
        String key = environmentKey;
        if (stored != null
                && stored.getEncryptedKey() != null
                && !stored.getEncryptedKey().isBlank()) {
            key = decrypt(stored.getEncryptedKey());
        }
        return new RuntimeConfig(
                stored == null ? environmentBase : stored.getBaseUrl(),
                stored == null ? environmentModel : stored.getModel(),
                key,
                stored == null ? environmentEnabled : stored.isEnabled());
    }

    public Map<String, Object> overview() {
        RuntimeConfig c;
        boolean keyReadable = true;
        try {
            c = current();
        } catch (IllegalArgumentException e) {
            VisualAiConfig row = repository.findById(1L).orElse(null);
            c = new RuntimeConfig(
                    row == null ? environmentBase : row.getBaseUrl(),
                    row == null ? environmentModel : row.getModel(),
                    "",
                    false);
            keyReadable = false;
        }
        return Map.of(
                "baseUrl",
                c.baseUrl,
                "model",
                c.model,
                "enabled",
                c.enabled,
                "keyConfigured",
                !c.apiKey.isBlank(),
                "keyStorageAvailable",
                encryptionSecret.length() >= 32,
                "message",
                !keyReadable
                        ? "AI 密钥无法解密，请恢复服务器加密配置或重新保存密钥"
                        : encryptionSecret.length() < 32 ? "服务器可使用环境变量密钥；网页保存密钥需配置至少32位 VISUAL_AI_ENCRYPTION_KEY" : "");
    }

    public synchronized Map<String, Object> save(Map<String, Object> body) {
        String base = String.valueOf(body.getOrDefault("baseUrl", "")).trim().replaceAll("/+$", "");
        String model = String.valueOf(body.getOrDefault("model", "")).trim();
        validateBaseUrl(base);
        if (model.isBlank() || model.length() > 150 || model.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请输入有效的 AI 模型名称");
        if (!(body.get("enabled") instanceof Boolean enabled)) throw new IllegalArgumentException("请设置是否启用 AI");
        String apiKey = body.get("apiKey") instanceof String s ? s.trim() : "";
        if (apiKey.length() > 4000 || apiKey.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("AI 密钥格式无效");
        VisualAiConfig row = repository.findById(1L).orElseGet(VisualAiConfig::new);
        row.setId(1L);
        row.setBaseUrl(base);
        row.setModel(model);
        row.setEnabled(enabled);
        if (Boolean.TRUE.equals(body.get("clearKey"))) row.setEncryptedKey(null);
        else if (!apiKey.isBlank()) row.setEncryptedKey(encrypt(apiKey));
        repository.saveAndFlush(row);
        return overview();
    }

    static void validateBaseUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            if (raw.length() > 500
                    || !"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) throw new Exception();
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64
                        || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) throw new Exception();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 服务地址须为可访问的公网 HTTPS 地址");
        }
    }

    private String encrypt(String value) {
        if (encryptionSecret.length() < 32)
            throw new IllegalArgumentException("服务器须先配置至少32位 VISUAL_AI_ENCRYPTION_KEY 才能保存密钥");
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 密钥加密失败");
        }
    }

    private String decrypt(String value) {
        try {
            if (encryptionSecret.length() < 32) throw new Exception();
            String[] parts = value.split("\\.");
            return new String(
                    cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[0]))
                            .doFinal(Base64.getDecoder().decode(parts[1])),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 密钥无法解密");
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                mode,
                new SecretKeySpec(
                        MessageDigest.getInstance("SHA-256").digest(encryptionSecret.getBytes(StandardCharsets.UTF_8)),
                        "AES"),
                new GCMParameterSpec(128, iv));
        return cipher;
    }

    public static String digest(String text) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
