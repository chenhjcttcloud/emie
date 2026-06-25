package com.emie.designpm.controller;

import com.emie.designpm.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private Path uploadPath;

    @PostConstruct
    public void init() {
        uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建上传目录: " + uploadPath, e);
        }
    }

    /**
     * 上传单个文件（流式写入磁盘，不加载到内存）
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !SecurityUtil.isValidFileName(originalName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "不允许上传此类型文件"));
        }

        // 生成唯一文件名防止冲突
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) ext = originalName.substring(dot).toLowerCase();
        String storedName = UUID.randomUUID().toString() + ext;

        try {
            // 流式写入磁盘
            Path targetPath = uploadPath.resolve(storedName).normalize();
            if (!targetPath.startsWith(uploadPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件名非法"));
            }
            file.transferTo(targetPath.toFile());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", originalName);
            result.put("storedName", storedName);
            result.put("size", file.getSize());
            result.put("url", "/api/files/download/" + storedName);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "文件保存失败: " + e.getMessage()));
        }
    }

    /**
     * 文件下载/查看（支持子目录如 admin/xxx）
     */
    @GetMapping("/download/{subDir}/{fileName}")
    public ResponseEntity<Object> downloadFileInSubDir(@PathVariable String subDir, @PathVariable String fileName) {
        try {
            Path dirPath = uploadPath.resolve(subDir).normalize();
            if (!dirPath.startsWith(uploadPath)) {
                return ResponseEntity.badRequest().build();
            }
            Path filePath = dirPath.resolve(fileName).normalize();
            if (!filePath.startsWith(dirPath) || !Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            String mimeType = URLConnection.guessContentTypeFromName(fileName);
            if (mimeType == null) mimeType = "application/octet-stream";

            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(filePath.toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header("Content-Disposition", "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 文件下载/查看（根目录）
     */
    @GetMapping("/download/{fileName}")
    public ResponseEntity<Object> downloadFile(@PathVariable String fileName) {
        try {
            Path filePath = uploadPath.resolve(fileName).normalize();
            if (!filePath.startsWith(uploadPath) || !Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            // 检测 MIME 类型
            String mimeType = URLConnection.guessContentTypeFromName(fileName);
            if (mimeType == null) mimeType = "application/octet-stream";

            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(filePath.toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header("Content-Disposition", "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
