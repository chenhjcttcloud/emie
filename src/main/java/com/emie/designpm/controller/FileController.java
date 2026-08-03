package com.emie.designpm.controller;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.service.FilePreviewService;
import com.emie.designpm.service.FileThumbnailService;
import com.emie.designpm.service.ProjectAccessService;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private Path uploadPath;
    private final FileArchiveService fileArchiveService;
    private final FileRecordRepository fileRecordRepository;
    private final ProjectAccessService projectAccessService;
    private final SubTaskRepository subTaskRepository;
    private final DesignRequirementRepository designRequirementRepository;
    private final FilePreviewService filePreviewService;
    private final FileThumbnailService fileThumbnailService;

    @Autowired
    public FileController(FileArchiveService fileArchiveService,
                          FileRecordRepository fileRecordRepository,
                          ProjectAccessService projectAccessService,
                          SubTaskRepository subTaskRepository,
                          FilePreviewService filePreviewService,
                          FileThumbnailService fileThumbnailService,
                          DesignRequirementRepository designRequirementRepository) {
        this.fileArchiveService = fileArchiveService;
        this.fileRecordRepository = fileRecordRepository;
        this.projectAccessService = projectAccessService;
        this.subTaskRepository = subTaskRepository;
        this.filePreviewService = filePreviewService;
        this.fileThumbnailService = fileThumbnailService;
        this.designRequirementRepository = designRequirementRepository;
    }

    /** 保留旧测试/嵌入式调用方的构造器兼容性。 */
    public FileController(FileArchiveService fileArchiveService,
                          FileRecordRepository fileRecordRepository,
                          ProjectAccessService projectAccessService,
                          SubTaskRepository subTaskRepository,
                          FilePreviewService filePreviewService,
                          FileThumbnailService fileThumbnailService) {
        this(fileArchiveService, fileRecordRepository, projectAccessService, subTaskRepository,
                filePreviewService, fileThumbnailService, null);
    }

    /** 保留旧测试/嵌入式调用方的构造器兼容性。 */
    public FileController(FileArchiveService fileArchiveService,
                          FileRecordRepository fileRecordRepository,
                          ProjectAccessService projectAccessService,
                          SubTaskRepository subTaskRepository,
                          FilePreviewService filePreviewService) {
        this(fileArchiveService, fileRecordRepository, projectAccessService, subTaskRepository,
                filePreviewService, new FileThumbnailService(fileArchiveService));
    }

    /** 读取受权限保护的缩略图；原图仅用于点击后的大图预览。 */
    @GetMapping("/thumbnail/{fileName}")
    public ResponseEntity<Object> thumbnail(@PathVariable String fileName, HttpServletRequest request) {
        if (!SecurityUtil.isValidFileName(fileName) || !fileName.matches("(?i).+\\.(png|jpe?g|gif|webp|bmp)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "不是支持的图片文件"));
        }
        ResponseEntity<Object> authResult = checkDownloadAccess(null, fileName, request);
        if (authResult != null) return authResult;
        try {
            Path thumbnail = fileThumbnailService.getOrCreate(fileName, uploadPath.resolve("thumbnail-cache"));
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                    .lastModified(Files.getLastModifiedTime(thumbnail).toMillis())
                    .body(new UrlResource(thumbnail.toUri()));
        } catch (FileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            log.warn("缩略图生成失败 storedName={}: {}", fileName, e.getMessage());
            return ResponseEntity.status(422).body(Map.of("error", "缩略图生成失败"));
        }
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
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file,
                                                          HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !SecurityUtil.isValidAttachmentFile(originalName)) {
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
                    null, null,
                    Optional.ofNullable((AuthController.AuthSession) request.getAttribute("authSession"))
                            .map(AuthController.AuthSession::userId).orElse(null));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", originalName);
            result.put("storedName", storedName);
            result.put("size", file.getSize());
            result.put("url", "/api/files/download/" + storedName);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "文件保存失败，请稍后重试"));
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

    /** 查询或启动文件预览生成。PDF 直接就绪，PPT/PPTX 在后台转换。 */
    @GetMapping("/preview/status/{fileName}")
    public ResponseEntity<Map<String, Object>> previewStatus(
            @PathVariable String fileName,
            @RequestParam(value = "retry", required = false, defaultValue = "false") boolean retry,
            HttpServletRequest request) {
        if (!SecurityUtil.isValidFileName(fileName) || !filePreviewService.isPreviewable(fileName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "unsupported",
                    "message", "该文件类型暂不支持在线预览"));
        }
        ResponseEntity<Object> authResult = checkDownloadAccess(null, fileName, request);
        if (authResult != null) {
            return ResponseEntity.status(authResult.getStatusCode()).body(Map.of(
                    "status", "failed",
                    "message", "无权访问该文件"));
        }

        FilePreviewService.PreviewStatus status = filePreviewService.preparePreview(fileName, retry);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.status());
        result.put("message", status.message());
        if ("ready".equals(status.status())) {
            result.put("previewUrl", "/api/files/preview/"
                    + UriUtils.encodePathSegment(fileName, java.nio.charset.StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok(result);
    }

    /** 返回统一的 PDF 预览内容。 */
    @GetMapping("/preview/{fileName}")
    public ResponseEntity<Object> previewFile(@PathVariable String fileName,
                                              HttpServletRequest request) {
        if (!SecurityUtil.isValidFileName(fileName) || !filePreviewService.isPreviewable(fileName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "该文件类型暂不支持在线预览"));
        }
        ResponseEntity<Object> authResult = checkDownloadAccess(null, fileName, request);
        if (authResult != null) {
            return authResult;
        }
        try {
            Path previewPath = filePreviewService.resolvePreviewFile(fileName);
            if (previewPath == null) {
                FilePreviewService.PreviewStatus status = filePreviewService.preparePreview(fileName, false);
                int httpStatus = "failed".equals(status.status()) ? 503 : 202;
                return ResponseEntity.status(httpStatus).body(Map.of(
                        "status", status.status(),
                        "message", status.message()));
            }
            Resource resource = new UrlResource(previewPath.toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(Files.size(previewPath))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(resource);
        } catch (FileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "文件预览加载失败"));
        }
    }

    /** 获取文件基本信息（不含文件内容） */
    @GetMapping("/info/{fileName}")
    public ResponseEntity<Map<String, Object>> fileInfo(@PathVariable String fileName,
                                                        HttpServletRequest request) {
        ResponseEntity<Object> authResult = checkDownloadAccess(null, fileName, request);
        if (authResult != null) {
            return ResponseEntity.status(authResult.getStatusCode()).body(Map.of("error", "无权访问该文件"));
        }
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
                    // 历史文件可能没有 owner/target 绑定，但仍被业务数据 JSON 引用。
                    // 按当前用户可见的项目、子任务及设计需求判断，避免历史文件被误判为无权访问。
                    if (record.getTargetType() == null && record.getTargetId() == null) {
                        return (record.getOwnerUserId() != null && session.userId().equals(record.getOwnerUserId()))
                                || isFileVisibleInAccessibleProjects(session, storedName, relativePath)
                                || isFileVisibleInAccessibleDesignRequirements(session, storedName);
                    }
                    return canAccessBoundTarget(session, record.getTargetType(), record.getTargetId())
                            || isFileVisibleInAccessibleProjects(session, storedName, relativePath)
                            || isFileVisibleInAccessibleDesignRequirements(session, storedName);
                })
                .orElseGet(() -> isFileVisibleInAccessibleProjects(session, storedName, relativePath)
                        || isFileVisibleInAccessibleDesignRequirements(session, storedName));
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
        List<Project> accessibleProjects = getAccessibleProjectsWithTasks(session);
        for (Project project : accessibleProjects) {
            if (jsonContainsFile(project.getReferenceImagesJson(), storedName, relativePath)
                    || jsonContainsFile(project.getAttachmentsJson(), storedName, relativePath)) {
                return true;
            }
        }
        List<Long> projectIds = accessibleProjects.stream()
                .map(Project::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return !projectIds.isEmpty()
                && subTaskRepository.countFileReferencesByProjectIds(projectIds, storedName) > 0;
    }

    private List<Project> getAccessibleProjectsWithTasks(AuthController.AuthSession session) {
        return projectAccessService.findVisibleProjectsWithTasks(session);
    }

    private boolean isFileVisibleInAccessibleDesignRequirements(AuthController.AuthSession session,
                                                                 String storedName) {
        return designRequirementRepository != null
                && designRequirementRepository.countVisibleFileReferences(session.userId(), storedName) > 0;
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
