package com.emie.designpm.controller;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.MaterialMarketItemRepository;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final Semaphore UPLOAD_SLOTS = new Semaphore(4);
    /** 缩略图请求允许排队，实际图片生成仍由 FileThumbnailService 控制并发。 */
    private static final Semaphore THUMBNAIL_REQUEST_SLOTS = new Semaphore(32);
    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 仅放行 AdminService.uploadAdminImage 生成的管理图片（admin_{logo|login-bg}_{8位hex}.{图片扩展名}）。 */
    private static final Pattern ADMIN_MANAGED_IMAGE = Pattern.compile(
            "admin_(logo|login-bg)_[0-9a-f]{8}\\.(jpg|jpeg|png|gif|bmp|webp)");

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    private Path uploadPath;
    private final FileArchiveService fileArchiveService;
    private final FileRecordRepository fileRecordRepository;
    private final ProjectRepository projectRepository;
    private final MaterialMarketItemRepository materialMarketRepository;
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
                          DesignRequirementRepository designRequirementRepository,
                          ProjectRepository projectRepository,
                          MaterialMarketItemRepository materialMarketRepository) {
        this.fileArchiveService = fileArchiveService;
        this.fileRecordRepository = fileRecordRepository;
        this.projectAccessService = projectAccessService;
        this.subTaskRepository = subTaskRepository;
        this.filePreviewService = filePreviewService;
        this.fileThumbnailService = fileThumbnailService;
        this.designRequirementRepository = designRequirementRepository;
        this.projectRepository = projectRepository;
        this.materialMarketRepository = materialMarketRepository;
    }

    /** 保留旧测试/嵌入式调用方的构造器兼容性。 */
    public FileController(FileArchiveService fileArchiveService,
                          FileRecordRepository fileRecordRepository,
                          ProjectAccessService projectAccessService,
                          SubTaskRepository subTaskRepository,
                          FilePreviewService filePreviewService,
                          FileThumbnailService fileThumbnailService,
                          DesignRequirementRepository designRequirementRepository) {
        this(fileArchiveService, fileRecordRepository, projectAccessService, subTaskRepository,
                filePreviewService, fileThumbnailService, designRequirementRepository, null, null);
    }

    /** 保留旧测试/嵌入式调用方的构造器兼容性。 */
    public FileController(FileArchiveService fileArchiveService,
                          FileRecordRepository fileRecordRepository,
                          ProjectAccessService projectAccessService,
                          SubTaskRepository subTaskRepository,
                          FilePreviewService filePreviewService,
                          FileThumbnailService fileThumbnailService) {
        this(fileArchiveService, fileRecordRepository, projectAccessService, subTaskRepository,
                filePreviewService, fileThumbnailService, null, null, null);
    }

    /** 保留旧测试/嵌入式调用方的构造器兼容性。 */
    public FileController(FileArchiveService fileArchiveService,
                          FileRecordRepository fileRecordRepository,
                          ProjectAccessService projectAccessService,
                          SubTaskRepository subTaskRepository,
                          FilePreviewService filePreviewService) {
        this(fileArchiveService, fileRecordRepository, projectAccessService, subTaskRepository,
                filePreviewService, new FileThumbnailService(fileArchiveService), null, null, null);
    }

    /** 读取受权限保护的缩略图；原图仅用于点击后的大图预览。 */
    @GetMapping("/thumbnail/{fileName}")
    public ResponseEntity<Object> thumbnail(@PathVariable String fileName, HttpServletRequest request) {
        if (!SecurityUtil.isValidFileName(fileName) || !fileName.matches("(?i).+\\.(png|jpe?g|gif|webp|bmp|ai)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "不是支持的图片文件"));
        }
        boolean acquired = false;
        try {
            acquired = THUMBNAIL_REQUEST_SLOTS.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) {
                return ResponseEntity.status(429).body(Map.of("error", "缩略图请求较多，请稍后重试"));
            }
            ResponseEntity<Object> authResult = checkDownloadAccess(null, fileName, request);
            if (authResult != null) return authResult;
            Path thumbnail = fileThumbnailService.getOrCreate(fileName, uploadPath.resolve("thumbnail-cache"));
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                    .lastModified(Files.getLastModifiedTime(thumbnail).toMillis())
                    .body(new UrlResource(thumbnail.toUri()));
        } catch (FileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            log.warn("缩略图生成失败 storedName={}: {}", fileName, e.getMessage());
            return ResponseEntity.status(422).body(Map.of("error", "缩略图生成失败"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(429).body(Map.of("error", "缩略图请求被中断，请稍后重试"));
        } finally {
            if (acquired) THUMBNAIL_REQUEST_SLOTS.release();
        }
    }

    /** 兼容历史记录：仅保存了原始文件名、未保存 storedName 的图片。 */
    @GetMapping("/thumbnail-by-original")
    public ResponseEntity<Object> thumbnailByOriginal(@RequestParam String name, HttpServletRequest request) {
        for (var record : fileRecordRepository.findByOriginalNameOrderByCreatedAtDesc(name)) {
            ResponseEntity<Object> response = thumbnail(record.getStoredName(), request);
            if (response.getStatusCode().is2xxSuccessful()) return response;
        }
        return ResponseEntity.notFound().build();
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
        boolean acquired = false;
        try {
            acquired = UPLOAD_SLOTS.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(429).body(Map.of("error", "上传任务排队被中断，请稍后重试"));
        }
        if (!acquired) {
            return ResponseEntity.status(429).body(Map.of("error", "当前上传任务较多，请稍后重试"));
        }
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
            }
            AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
            long maxBytes = 200L * 1024 * 1024;
            if (file.getSize() > maxBytes) {
                return ResponseEntity.status(413).body(Map.of("error", "文件超过当前角色允许的大小限制"));
            }

            String originalName = file.getOriginalFilename();
            if (originalName == null || !SecurityUtil.isValidAttachmentFile(originalName)) {
                return ResponseEntity.badRequest().body(Map.of("error", "不允许上传此类型文件"));
            }

            String ext = "";
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) ext = originalName.substring(dot).toLowerCase();
            String storedName = UUID.randomUUID().toString() + ext;

            Path targetPath = uploadPath.resolve(storedName).normalize();
            if (!targetPath.startsWith(uploadPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件名非法"));
            }
            try {
                file.transferTo(targetPath.toFile());

                String mimeType = URLConnection.guessContentTypeFromName(originalName);
                fileArchiveService.recordUpload(storedName, originalName, file.getSize(),
                        mimeType != null ? mimeType : "application/octet-stream",
                        null, null,
                                Optional.ofNullable(session)
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
        } finally {
            UPLOAD_SLOTS.release();
        }
    }

    @GetMapping("/library")
    public ResponseEntity<Object> library(HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null || !Set.of("admin", "planner", "designer").contains(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "没有权限查看图档库"));
        }
        List<Map<String, Object>> items = fileRecordRepository.findByTargetTypeOrderByCreatedAtDesc("image_library")
                .stream().map(this::libraryItem).toList();
        return ResponseEntity.ok(items);
    }

    @PostMapping("/library/upload")
    public ResponseEntity<Map<String, Object>> uploadLibraryImage(@RequestParam("file") MultipartFile file,
                                                                    HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        if (session == null || !Set.of("admin", "planner").contains(session.role())) {
            return ResponseEntity.status(403).body(Map.of("error", "仅产品企划和管理员可以上传图档"));
        }
        if (file.isEmpty() || file.getOriginalFilename() == null || !SecurityUtil.isValidAttachmentFile(file.getOriginalFilename())
                || file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传有效的图片文件"));
        }
        if (file.getSize() > 200L * 1024 * 1024) return ResponseEntity.status(413).body(Map.of("error", "图片不能超过 200MB"));
        try {
            String originalName = file.getOriginalFilename();
            String ext = originalName.substring(originalName.lastIndexOf('.')).toLowerCase();
            String storedName = UUID.randomUUID() + ext;
            file.transferTo(uploadPath.resolve(storedName).normalize().toFile());
            fileArchiveService.recordUpload(storedName, originalName, file.getSize(), file.getContentType(),
                    "image_library", null, session.userId());
            return ResponseEntity.ok(libraryItem(fileRecordRepository.findByStoredName(storedName).orElseThrow()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "图片保存失败，请稍后重试"));
        }
    }

    private Map<String, Object> libraryItem(FileRecord file) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", file.getId()); item.put("name", file.getOriginalName()); item.put("size", file.getFileSize());
        item.put("createdAt", file.getCreatedAt()); item.put("ownerUserId", file.getOwnerUserId());
        item.put("storedName", file.getStoredName());
        item.put("url", "/api/files/download/" + file.getStoredName());
        item.put("thumbnailUrl", "/api/files/thumbnail/" + file.getStoredName());
        return item;
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
                return serveFile(filePath, downloadName(subDir + "/" + fileName), forceDownload);
            }
            try {
                filePath = fileArchiveService.resolveFile(subDir + "/" + fileName);
                return serveFile(filePath, downloadName(subDir + "/" + fileName), forceDownload);
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
                return serveFile(filePath, downloadName(fileName), forceDownload);
            }
            try {
                filePath = fileArchiveService.resolveFile(fileName);
                return serveFile(filePath, downloadName(fileName), forceDownload);
            } catch (Exception e) {
                return ResponseEntity.notFound().build();
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 兼容历史记录：仅保存了原始文件名、未保存 storedName 的文件。 */
    @GetMapping("/download-by-original")
    public ResponseEntity<Object> downloadByOriginal(
            @RequestParam String name,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean forceDownload,
            HttpServletRequest request) {
        for (var record : fileRecordRepository.findByOriginalNameOrderByCreatedAtDesc(name)) {
            ResponseEntity<Object> response = downloadFile(record.getStoredName(), forceDownload, request);
            if (response.getStatusCode().is2xxSuccessful()) return response;
        }
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<Object> serveFile(Path filePath, String fileName, boolean forceDownload) throws IOException {
        fileName = fileName.replaceAll("[\\r\\n\\\"\\\\/]", "_");
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        if (mimeType == null) mimeType = "application/octet-stream";
        Resource resource = new UrlResource(filePath.toUri());
        String asciiName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = (forceDownload ? "attachment" : "inline")
                + "; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header("Content-Disposition", disposition)
                .body(resource);
    }

    private String downloadName(String storedName) {
        return fileRecordRepository.findByStoredName(storedName)
                .map(record -> record.getOriginalName())
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> storedName.substring(storedName.lastIndexOf('/') + 1));
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
        // 管理图片（logo/登录背景）需要匿名可访问，但仅限 AdminService 生成规则的图片；
        // 其它位于 admin 子目录的文件必须走正常权限校验，避免绕过授权读取。
        if ("admin".equals(subDir) && fileName != null && ADMIN_MANAGED_IMAGE.matcher(fileName).matches()) {
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
                                || isFileVisibleInMaterialMarket(storedName)
                                || isFileVisibleInAccessibleDesignRequirements(session, storedName);
                    }
                    return canAccessBoundTarget(session, record.getTargetType(), record.getTargetId())
                            || isFileVisibleInAccessibleProjects(session, storedName, relativePath)
                            || isFileVisibleInMaterialMarket(storedName)
                            || isFileVisibleInAccessibleDesignRequirements(session, storedName);
                })
                .orElseGet(() -> isFileVisibleInAccessibleProjects(session, storedName, relativePath)
                        || isFileVisibleInMaterialMarket(storedName)
                        || isFileVisibleInAccessibleDesignRequirements(session, storedName));
    }

    private boolean canAccessBoundTarget(AuthController.AuthSession session, String targetType, Long targetId) {
        // 图档库是集合型资源，不绑定单个业务 targetId；必须先于 targetId 空值判断处理。
        if ("image_library".equals(targetType)) {
            return Set.of("admin", "planner", "designer").contains(session.role());
        }
        if (targetType == null || targetType.isBlank() || targetId == null) {
            return false;
        }
        List<Long> visibleProjectIds = projectAccessService.findVisibleProjectIds(session.role(), session.userId());
        if (visibleProjectIds.isEmpty()) {
            return switch (targetType) {
                case "project" -> getAccessibleProjectsWithTasks(session).stream().anyMatch(project -> targetId.equals(project.getId()));
                case "sub_task" -> getAccessibleProjectsWithTasks(session).stream().flatMap(project -> project.getTasks().stream())
                        .anyMatch(task -> targetId.equals(task.getId()));
                default -> false;
            };
        }
        return switch (targetType) {
            case "project" -> visibleProjectIds.contains(targetId);
            case "sub_task" -> subTaskRepository.findProjectIdById(targetId)
                    .map(visibleProjectIds::contains).orElse(false);
            case "material_market" -> materialMarketRepository != null && materialMarketRepository.existsById(targetId);
            case "admin" -> false;
            default -> false;
        };
    }

    private boolean isFileVisibleInAccessibleProjects(AuthController.AuthSession session, String storedName, String relativePath) {
        List<Long> projectIds = projectAccessService.findVisibleProjectIds(session.role(), session.userId());
        if (projectIds.isEmpty() || projectRepository == null) {
            List<Project> accessibleProjects = getAccessibleProjectsWithTasks(session);
            for (Project project : accessibleProjects) {
                if (jsonContainsFile(project.getReferenceImagesJson(), storedName, relativePath)
                        || jsonContainsFile(project.getAttachmentsJson(), storedName, relativePath)) return true;
            }
            projectIds = accessibleProjects.stream().map(Project::getId).filter(Objects::nonNull).distinct().toList();
            if (projectIds.isEmpty()) return false;
        }
        return !projectIds.isEmpty()
                && (projectRepository != null && projectRepository.countFileReferencesByProjectIds(projectIds, storedName) > 0
                || subTaskRepository.countFileReferencesByProjectIds(projectIds, storedName) > 0);
    }

    private List<Project> getAccessibleProjectsWithTasks(AuthController.AuthSession session) {
        return projectAccessService.findVisibleProjectsWithTasks(session);
    }

    private boolean isFileVisibleInAccessibleDesignRequirements(AuthController.AuthSession session,
                                                                 String storedName) {
        return designRequirementRepository != null
                && designRequirementRepository.countVisibleFileReferences(session.userId(), storedName) > 0;
    }

    private boolean isFileVisibleInMaterialMarket(String storedName) {
        return materialMarketRepository != null && materialMarketRepository.countFileReferencesByStoredName(storedName) > 0;
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
