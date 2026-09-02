package com.emie.designpm.service;

import com.emie.designpm.entity.FeishuAttachmentCache;
import com.emie.designpm.repository.FeishuAttachmentCacheRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 将系统文件真实上传为飞书多维表格附件，并持久化 file_token。 */
@Service
public class FeishuAttachmentService {
    static final long UPLOAD_ALL_LIMIT = 20L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final FileArchiveService files;
    private final FeishuAttachmentCacheRepository cache;
    private final PermanentFileLinkService permanentLinks;
    private final ConcurrentHashMap<String, Object> uploadLocks = new ConcurrentHashMap<>();

    @Autowired
    public FeishuAttachmentService(FileArchiveService files, FeishuAttachmentCacheRepository cache,
                                   PermanentFileLinkService permanentLinks) {
        this.files = files;
        this.cache = cache;
        this.permanentLinks = permanentLinks;
    }

    public FeishuAttachmentService(FileArchiveService files, FeishuAttachmentCacheRepository cache) {
        this(files, cache, null);
    }

    public List<String> uploadJsonFiles(String rawJson, String appToken, String tenantToken) throws Exception {
        List<String> tokens = new ArrayList<>();
        if (rawJson == null || rawJson.isBlank()) return tokens;
        JsonNode root = JSON.readTree(rawJson);
        if (!root.isArray()) return tokens;
        for (JsonNode item : root) {
            String storedName = item.path("storedName").asText("");
            if (storedName.isBlank()) storedName = storedNameFromUrl(item.path("url").asText(""));
            if (storedName.isBlank()) continue;
            String originalName = item.path("name").asText(storedName);
            if (Files.size(files.resolveFile(storedName)) <= UPLOAD_ALL_LIMIT)
                tokens.add(resolveOrUpload(appToken, tenantToken, storedName, originalName));
        }
        return tokens;
    }

    public List<OversizedFile> oversizedJsonFiles(String rawJson) throws Exception {
        List<OversizedFile> result = new ArrayList<>();
        if (rawJson == null || rawJson.isBlank() || permanentLinks == null) return result;
        JsonNode root = JSON.readTree(rawJson);
        if (!root.isArray()) return result;
        for (JsonNode item : root) {
            String storedName = item.path("storedName").asText("");
            if (storedName.isBlank()) storedName = storedNameFromUrl(item.path("url").asText(""));
            if (storedName.isBlank()) continue;
            Path path = files.resolveFile(storedName); long size = Files.size(path);
            if (size > UPLOAD_ALL_LIMIT) result.add(new OversizedFile(
                    item.path("name").asText(storedName), size, permanentLinks.create(storedName)));
        }
        return result;
    }

    public record OversizedFile(String name, long size, String url) {}

    public String resolveOrUpload(String appToken, String tenantToken,
                                  String storedName, String originalName) throws Exception {
        String key = appToken + "\n" + storedName;
        Object lock = uploadLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                return resolveOrUploadLocked(appToken, tenantToken, storedName, originalName);
            }
        } finally {
            uploadLocks.remove(key, lock);
        }
    }

    private String resolveOrUploadLocked(String appToken, String tenantToken,
                                         String storedName, String originalName) throws Exception {
        Path path = files.resolveFile(storedName);
        long size = Files.size(path);
        long modified = Files.getLastModifiedTime(path).toMillis();
        FeishuAttachmentCache existing = cache.findByAppTokenAndStoredName(appToken, storedName).orElse(null);
        if (existing != null && existing.getFileSize() == size && existing.getModifiedMillis() == modified) {
            return existing.getFileToken();
        }
        if (size > UPLOAD_ALL_LIMIT) {
            throw new IllegalArgumentException("飞书附件超过 20MB，暂无法上传: " + originalName);
        }
        String token = upload(path, safeName(originalName), size, appToken, tenantToken);
        FeishuAttachmentCache row = existing != null ? existing : new FeishuAttachmentCache();
        row.setAppToken(appToken);
        row.setStoredName(storedName);
        row.setFileSize(size);
        row.setModifiedMillis(modified);
        row.setFileToken(token);
        row.setUploadedAt(LocalDateTime.now());
        cache.save(row);
        return token;
    }

    private String upload(Path path, String fileName, long size,
                          String appToken, String tenantToken) throws Exception {
        String boundary = "----emie-" + UUID.randomUUID();
        byte[] file = Files.readAllBytes(path);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writePart(out, boundary, "file_name", fileName);
        writePart(out, boundary, "parent_type", "bitable_file");
        writePart(out, boundary, "parent_node", appToken);
        writePart(out, boundary, "size", Long.toString(size));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName.replace("\"", "_") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.feishu.cn/open-apis/drive/v1/medias/upload_all"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + tenantToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(response.body());
        if (response.statusCode() / 100 != 2 || root.path("code").asInt(-1) != 0) {
            throw new Exception("飞书附件上传失败: " + root.path("msg").asText("HTTP " + response.statusCode()));
        }
        String fileToken = root.path("data").path("file_token").asText("");
        if (fileToken.isBlank()) throw new Exception("飞书附件上传失败: 缺少 file_token");
        return fileToken;
    }

    private static void writePart(java.io.ByteArrayOutputStream out, String boundary,
                                  String name, String value) throws java.io.IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String storedNameFromUrl(String url) {
        if (url == null || url.isBlank()) return "";
        int query = url.indexOf('?');
        String clean = query >= 0 ? url.substring(0, query) : url;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private static String safeName(String name) {
        String cleaned = name == null ? "attachment" : name.replaceAll("[\\r\\n\\\\/]", "_").trim();
        return cleaned.isBlank() ? "attachment" : cleaned;
    }
}
