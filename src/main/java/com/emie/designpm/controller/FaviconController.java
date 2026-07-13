package com.emie.designpm.controller;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.SystemConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** 浏览器标签页图标。优先复用后台配置的 app.logo。 */
@RestController
public class FaviconController {

    private static final byte[] DEFAULT_ICON = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
              <rect width="64" height="64" rx="14" fill="#3370ff"/>
              <text x="32" y="45" text-anchor="middle" font-size="38">🎨</text>
            </svg>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final SystemConfigRepository configRepository;
    private final Path uploadPath;

    public FaviconController(SystemConfigRepository configRepository,
                             @Value("${app.upload.dir:./uploads}") String uploadDir) {
        this.configRepository = configRepository;
        this.uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping(value = "/favicon.ico", produces = {"image/x-icon", "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml"})
    public ResponseEntity<byte[]> favicon() {
        String logoUrl = configRepository.findByConfigKey("app.logo")
                .map(SystemConfig::getConfigValue).orElse("");
        if (logoUrl.startsWith("/api/files/download/admin/")) {
            String fileName = logoUrl.substring("/api/files/download/admin/".length());
            if (fileName.matches("[A-Za-z0-9._-]+")) {
                Path file = uploadPath.resolve("admin").resolve(fileName).normalize();
                if (file.startsWith(uploadPath.resolve("admin")) && Files.isRegularFile(file)) {
                    try {
                        return ResponseEntity.ok()
                                .contentType(contentType(fileName))
                                .cacheControl(CacheControl.noCache().mustRevalidate())
                                .body(Files.readAllBytes(file));
                    } catch (IOException ignored) {
                        // 回退到默认图标，避免 Logo 文件暂时不可读时仍返回 404。
                    }
                }
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).mustRevalidate())
                .body(DEFAULT_ICON);
    }

    private MediaType contentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.parseMediaType("image/svg+xml");
    }
}
