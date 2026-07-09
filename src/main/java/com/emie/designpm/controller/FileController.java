package com.emie.designpm.controller;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private Path uploadPath;
    private final FileArchiveService fileArchiveService;
    private final FileRecordRepository fileRecordRepository;
    private final ProjectRepository projectRepository;

    public FileController(FileArchiveService fileArchiveService,
                          FileRecordRepository fileRecordRepository,
                          ProjectRepository projectRepository) {
        this.fileArchiveService = fileArchiveService;
        this.fileRecordRepository = fileRecordRepository;
        this.projectRepository = projectRepository;
    }

    @PostConstruct
    public void init() {
        uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建上传目录: " + uploadPath, e);
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !SecurityUtil.isValidFileName(originalName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "不允许上传此类型文件"));
        }

        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) ext = originalName.substring(dot).toLowerCase();
        String storedName = UUID.randomUUID().toString() + ext;

        try {
            Path targetPath = uploadPath.resolve(storedName).normalize();
            if (!targetPath.startsWith(uploadPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件名非法"));
            }
            file.transferTo(targetPath.toFile());

            String mimeType = URLConnection.guessContentTypeFromName(originalName);
            fileArchiveService.recordUpload(storedName, originalName, file.getSize(),
                    mimeType != null ? mimeType : "application/octet-stream",
                    null, null);

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

    /** 下载文件，?download=true 强制浏览器下载（弹出保存对话框）*/
    @GetMapping("/download/{subDir}/{fileName}")
    public ResponseEntity<Object> downloadFileInSubDir(@PathVariable String subDir, @PathVariable String fileName,
                                                       @RequestParam(value = "download", required = false, defaultValue = "false") boolean forceDownload,
                                                       HttpServletRequest request) {
        ResponseEntity<Object> authResult = checkDownloadAccess(subDir, fileName, request);
        if (authResult != null) {
            return authResult;
        }
        try {
            Path filePath = uploadPath.resolve(subDir).resolve(fileName).normalize();
            if (filePath.startsWith(uploadPath) && Files.exists(filePath)) {
                return serveFile(filePath, fileName, forceDownload);
            }
            try {
                filePath = fileArchiveService.resolveFile(subDir + "/" + fileName);
                return serveFile(filePath, fileName, forceDownload);
            } catch (Exception e) {
                return ResponseEntity.notFound().build();
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 下载文件，?download=true 强制浏览器下载（弹出保存对话框）*/
    @GetMapping("/download/{fileName}")
    public ResponseEntity<Object> downloadFile(@PathVariable String fileName,
                                               @RequestParam(value = "download", required = false, defaultValue = "false") boolean forceDownload,
                                               HttpServletRequest request) {
        ResponseEntity<Object> authResult = checkDownloadAccess(null, fileName, request);
        if (authResult != null) {
            return authResult;
        }
        try {
            Path filePath = uploadPath.resolve(fileName).normalize();
            if (filePath.startsWith(uploadPath) && Files.exists(filePath)) {
                return serveFile(filePath, fileName, forceDownload);
            }
            try {
                filePath = fileArchiveService.resolveFile(fileName);
                return serveFile(filePath, fileName, forceDownload);
            } catch (Exception e) {
                return ResponseEntity.notFound().build();
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private ResponseEntity<Object> serveFile(Path filePath, String fileName, boolean forceDownload) throws IOException {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        if (mimeType == null) mimeType = "application/octet-stream";
        Resource resource = new UrlResource(filePath.toUri());
        String disposition = forceDownload
                ? "attachment; filename=\"" + fileName + "\""
                : "inline; filename=\"" + fileName + "\"";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header("Content-Disposition", disposition)
                .body(resource);
    }

    /** 获取文件基本信息（不含文件内容） */
    @GetMapping("/info/{fileName}")
    public ResponseEntity<Map<String, Object>> fileInfo(@PathVariable String fileName) {
        try {
            Path filePath = fileArchiveService.resolveFile(fileName);
            String mimeType = URLConnection.guessContentTypeFromName(fileName);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("fileName", fileName);
            info.put("fileSize", Files.size(filePath));
            info.put("mimeType", mimeType != null ? mimeType : "application/octet-stream");
            info.put("downloadUrl", "/api/files/download/" + fileName);
            return ResponseEntity.ok(info);
        } catch (FileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private ResponseEntity<Object> checkDownloadAccess(String subDir, String fileName, HttpServletRequest request) {
        if ("admin".equals(subDir)) {
            return null;
        }
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期，请重新登录"));
        }
        if ("admin".equals(session.role())) {
            return null;
        }
        String relativePath = subDir == null || subDir.isBlank() ? fileName : subDir + "/" + fileName;
        if (canAccessFile(session, fileName, relativePath)) {
            return null;
        }
        return ResponseEntity.status(403).body(Map.of("error", "无权访问该文件"));
    }

    private boolean canAccessFile(AuthController.AuthSession session, String storedName, String relativePath) {
        return fileRecordRepository.findByStoredName(storedName)
                .map(record -> {
                    // 尚未绑定到任何业务对象的文件（上传后、创建项目前预览），允许访问
                    if (record.getTargetType() == null && record.getTargetId() == null) {
                        return true;
                    }
                    return canAccessBoundTarget(session, record.getTargetType(), record.getTargetId())
                            || isFileVisibleInAccessibleProjects(session, storedName, relativePath);
                })
                .orElseGet(() -> isFileVisibleInAccessibleProjects(session, storedName, relativePath));
    }

    private boolean canAccessBoundTarget(AuthController.AuthSession session, String targetType, Long targetId) {
        if (targetType == null || targetType.isBlank() || targetId == null) {
            return false;
        }
        return switch (targetType) {
            case "project" -> getAccessibleProjectsWithTasks(session).stream()
                    .anyMatch(project -> targetId.equals(project.getId()));
            case "sub_task" -> getAccessibleProjectsWithTasks(session).stream()
                    .flatMap(project -> project.getTasks().stream())
                    .anyMatch(task -> targetId.equals(task.getId()));
            case "admin" -> false;
            default -> false;
        };
    }

    private boolean isFileVisibleInAccessibleProjects(AuthController.AuthSession session, String storedName, String relativePath) {
        for (Project project : getAccessibleProjectsWithTasks(session)) {
            if (jsonContainsFile(project.getReferenceImagesJson(), storedName, relativePath)
                    || jsonContainsFile(project.getAttachmentsJson(), storedName, relativePath)) {
                return true;
            }
            for (SubTask task : project.getTasks()) {
                if (jsonContainsFile(task.getReferenceImagesJson(), storedName, relativePath)
                        || jsonContainsFile(task.getAttachmentsJson(), storedName, relativePath)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Project> getAccessibleProjectsWithTasks(AuthController.AuthSession session) {
        String role = session.role();
        String userId = session.userId();
        if ("sales".equals(role)) {
            return projectRepository.findBySalesId(userId);
        }
        if ("planner".equals(role)) {
            return projectRepository.findByPlannerView(userId);
        }
        if ("designer".equals(role) || "supplychain".equals(role)) {
            return projectRepository.findByDesignerId(userId);
        }
        return projectRepository.findAllWithTasks();
    }

    private boolean jsonContainsFile(String json, String storedName, String relativePath) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            List<Map<String, Object>> files = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> file : files) {
                Object storedNameValue = file.get("storedName");
                if (storedName.equals(storedNameValue)) {
                    return true;
                }
                Object urlValue = file.get("url");
                if (urlValue instanceof String url) {
                    String normalizedUrl = url.split("\\?")[0];
                    if (normalizedUrl.endsWith("/" + storedName) || normalizedUrl.endsWith("/" + relativePath)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return json.contains(storedName) || json.contains(relativePath);
        }
    }
}
