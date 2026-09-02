package com.emie.designpm.service;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class PermanentFileLinkService {
    private static final String SECRET_KEY = "files.permanentLinkSecret";
    private final SystemConfigRepository configs;

    public PermanentFileLinkService(SystemConfigRepository configs) { this.configs = configs; }

    public String create(String storedName) {
        String base = configs.findByConfigKey("notification.publicBaseUrl")
                .map(SystemConfig::getConfigValue).orElse("").replaceAll("/+$", "");
        if (!base.matches("https?://.+")) return "";
        return base + "/api/files/permanent/" + sign(storedName) + "/" + storedName;
    }

    public boolean valid(String storedName, String signature) {
        return MessageDigest.isEqual(sign(storedName).getBytes(StandardCharsets.UTF_8),
                (signature == null ? "" : signature).getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String storedName) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(storedName.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法生成文件签名", e); }
    }

    private synchronized String secret() {
        SystemConfig row = configs.findByConfigKey(SECRET_KEY).orElse(null);
        if (row != null && row.getConfigValue() != null && !row.getConfigValue().isBlank()) return row.getConfigValue();
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (row == null) row = SystemConfig.builder().configKey(SECRET_KEY).configGroup("security")
                .description("永久文件链接签名密钥").valueType("password").sortOrder(999).build();
        row.setConfigValue(value); row.setUpdatedBy("system"); configs.save(row);
        return value;
    }
}
