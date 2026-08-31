package com.emie.designpm.controller;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.ImageLibraryItem;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ImageLibraryItemRepository;
import com.emie.designpm.repository.IpOptionRepository;
import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/image-library")
public class ImageLibraryController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VIEW_ROLES = Set.of("admin", "planner", "designer");
    private final ImageLibraryItemRepository items;
    private final FileRecordRepository files;
    private final IpOptionRepository ipOptions;
    private final FileArchiveService archive;

    public ImageLibraryController(ImageLibraryItemRepository items, FileRecordRepository files,
                                  IpOptionRepository ipOptions, FileArchiveService archive) {
        this.items = items; this.files = files; this.ipOptions = ipOptions; this.archive = archive;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        var session = session(request);
        if (session == null || !VIEW_ROLES.contains(session.role())) return ResponseEntity.status(403).body(Map.of("error", "没有权限查看图档库"));
        return ResponseEntity.ok(items.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        var session = session(request);
        if (session == null || !Set.of("admin", "planner").contains(session.role())) return ResponseEntity.status(403).body(Map.of("error", "仅产品企划和管理员可以上传图档"));
        String name = clean(body.get("name"), 160), ipName = clean(body.get("ipName"), 100), notes = clean(body.get("notes"), 1000);
        if (name == null) return bad("请输入图片名称");
        if (ipName == null || ipOptions.findByName(ipName).filter(ip -> Boolean.TRUE.equals(ip.getActive())).isEmpty()) return bad("请选择有效的 IP");
        List<String> subOptions = stringList(body.get("subOptions"), 20, 100);
        List<Map<String, Object>> images = mapList(body.get("images"));
        if (images.isEmpty() || images.size() > 3) return bad("请选择 1 至 3 张图片");
        List<FileRecord> records = new ArrayList<>();
        for (Map<String, Object> image : images) {
            String storedName = clean(image.get("storedName"), 255);
            FileRecord record = storedName == null ? null : files.findByStoredName(storedName).orElse(null);
            if (record == null || !session.userId().equals(record.getOwnerUserId()) || !isAllowedLibraryFile(record)) return bad("仅支持图片或 AI 文件");
            records.add(record);
        }
        try {
            ImageLibraryItem item = new ImageLibraryItem();
            item.setName(name); item.setIpName(ipName); item.setNotes(notes); item.setOwnerUserId(session.userId());
            item.setSubOptionsJson(JSON.writeValueAsString(subOptions)); item.setImagesJson(JSON.writeValueAsString(images));
            item = items.save(item);
            for (FileRecord record : records) { record.setTargetType("image_library"); record.setTargetId(item.getId()); }
            files.saveAll(records);
            return ResponseEntity.ok(view(item));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", "图档保存失败")); }
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        var session = session(request);
        if (!canManage(session)) return ResponseEntity.status(403).body(Map.of("error", "仅产品企划和管理员可以修改图档"));
        ImageLibraryItem item = items.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        String name = clean(body.get("name"), 160), ipName = clean(body.get("ipName"), 100), notes = clean(body.get("notes"), 1000);
        if (name == null) return bad("请输入图片名称");
        if (ipName == null || ipOptions.findByName(ipName).filter(ip -> Boolean.TRUE.equals(ip.getActive())).isEmpty()) return bad("请选择有效的 IP");
        List<String> subOptions = stringList(body.get("subOptions"), 20, 100);
        List<Map<String, Object>> images = mapList(body.get("images"));
        if (images.isEmpty() || images.size() > 3) return bad("请选择 1 至 3 张图片");
        List<FileRecord> selected = validateUpdateImages(images, item, session);
        if (selected == null) return bad("包含无效或无权使用的图片");
        try {
            Set<String> selectedNames = selected.stream().map(FileRecord::getStoredName).collect(java.util.stream.Collectors.toSet());
            List<FileRecord> removed = files.findByTargetTypeAndTargetId("image_library", id).stream()
                    .filter(record -> !selectedNames.contains(record.getStoredName())).toList();
            files.deleteAll(removed);
            item.setName(name); item.setIpName(ipName); item.setNotes(notes);
            item.setSubOptionsJson(JSON.writeValueAsString(subOptions)); item.setImagesJson(JSON.writeValueAsString(images));
            for (FileRecord record : selected) { record.setTargetType("image_library"); record.setTargetId(id); }
            files.saveAll(selected);
            return ResponseEntity.ok(view(items.save(item)));
        } catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", "图档修改失败")); }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        var session = session(request);
        if (!canManage(session)) return ResponseEntity.status(403).body(Map.of("error", "仅产品企划和管理员可以删除图档"));
        ImageLibraryItem item = items.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        files.deleteAll(files.findByTargetTypeAndTargetId("image_library", id));
        items.delete(item);
        return ResponseEntity.ok(Map.of("message", "图档已删除"));
    }

    @GetMapping("/{id}/download-all")
    public ResponseEntity<?> downloadAll(@PathVariable Long id, HttpServletRequest request) {
        var session = session(request);
        if (session == null || !VIEW_ROLES.contains(session.role())) return ResponseEntity.status(403).body(Map.of("error", "没有权限下载图档"));
        ImageLibraryItem item = items.findById(id).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        List<Map<String, Object>> imageList = parseMaps(item.getImagesJson());
        if (imageList.isEmpty()) return ResponseEntity.notFound().build();
        StreamingResponseBody body = output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                Set<String> usedNames = new HashSet<>();
                for (int index = 0; index < imageList.size(); index++) {
                    Map<String, Object> image = imageList.get(index);
                    String storedName = clean(image.get("storedName"), 255);
                    if (storedName == null) continue;
                    Path source;
                    try { source = archive.resolveFile(storedName); } catch (Exception e) { continue; }
                    String original = clean(image.get("name"), 255);
                    String entryName = uniqueZipName(original == null ? storedName : original, usedNames, index + 1);
                    zip.putNextEntry(new ZipEntry(entryName)); Files.copy(source, zip); zip.closeEntry();
                }
            }
        };
        String zipName = item.getName().replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_") + ".zip";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                        .filename(zipName, StandardCharsets.UTF_8).build().toString()).body(body);
    }

    private static String uniqueZipName(String raw, Set<String> used, int index) {
        String safe = raw.replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_");
        if (used.add(safe)) return safe;
        int dot = safe.lastIndexOf('.');
        String candidate = dot > 0 ? safe.substring(0, dot) + "-" + index + safe.substring(dot) : safe + "-" + index;
        used.add(candidate); return candidate;
    }

    private List<FileRecord> validateUpdateImages(List<Map<String, Object>> images, ImageLibraryItem item,
                                                   AuthController.AuthSession session) {
        List<FileRecord> result = new ArrayList<>();
        for (Map<String, Object> image : images) {
            String storedName = clean(image.get("storedName"), 255);
            FileRecord record = storedName == null ? null : files.findByStoredName(storedName).orElse(null);
            boolean existing = record != null && "image_library".equals(record.getTargetType()) && item.getId().equals(record.getTargetId());
            boolean newlyUploaded = record != null && session.userId().equals(record.getOwnerUserId()) && record.getTargetId() == null;
            if ((!existing && !newlyUploaded) || !isAllowedLibraryFile(record)) return null;
            result.add(record);
        }
        return result;
    }

    private Map<String, Object> view(ImageLibraryItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId()); result.put("name", item.getName()); result.put("ipName", item.getIpName());
        result.put("subOptions", parseList(item.getSubOptionsJson())); result.put("notes", item.getNotes());
        result.put("images", parseMaps(item.getImagesJson())); result.put("ownerUserId", item.getOwnerUserId()); result.put("createdAt", item.getCreatedAt());
        return result;
    }
    private static AuthController.AuthSession session(HttpServletRequest request) { return (AuthController.AuthSession) request.getAttribute("authSession"); }
    private static boolean canManage(AuthController.AuthSession session) { return session != null && Set.of("admin", "planner").contains(session.role()); }
    private static boolean isAllowedLibraryFile(FileRecord record) {
        return (record.getMimeType() != null && record.getMimeType().startsWith("image/"))
                || (record.getOriginalName() != null && record.getOriginalName().toLowerCase(Locale.ROOT).endsWith(".ai"));
    }
    private static ResponseEntity<?> bad(String message) { return ResponseEntity.badRequest().body(Map.of("error", message)); }
    private static String clean(Object value, int max) { if (value == null || value.toString().isBlank()) return null; String v = SecurityUtil.sanitizeText(value.toString(), max); return v.isBlank() ? null : v; }
    private static List<String> stringList(Object value, int maxCount, int maxLength) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().limit(maxCount).map(v -> clean(v, maxLength)).filter(Objects::nonNull).distinct().toList(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> mapList(Object value) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList(); }
    private static List<String> parseList(String json) { try { return json == null ? List.of() : JSON.readValue(json, new TypeReference<>() {}); } catch (Exception e) { return List.of(); } }
    private static List<Map<String, Object>> parseMaps(String json) { try { return json == null ? List.of() : JSON.readValue(json, new TypeReference<>() {}); } catch (Exception e) { return List.of(); } }
}
