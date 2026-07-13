package com.emie.designpm.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 为 PDF、PPT 和 PPTX 提供统一预览文件。
 * PDF 直接返回原文件；演示文稿通过独立转换服务生成 PDF 并缓存在本地。
 */
@Service
public class FilePreviewService {
    private static final Logger log = LoggerFactory.getLogger(FilePreviewService.class);
    private static final String READY = "ready";
    private static final String PROCESSING = "processing";
    private static final String FAILED = "failed";
    private static final String UNSUPPORTED = "unsupported";

    private final FileArchiveService fileArchiveService;
    private final Map<String, PreviewJob> jobs = new ConcurrentHashMap<>();

    @Value("${app.preview.cache-dir:./uploads/preview-cache}")
    private String cacheDir;

    @Value("${app.preview.converter-url:http://127.0.0.1:3000}")
    private String converterUrl;

    @Value("${app.preview.max-source-bytes:52428800}")
    private long maxSourceBytes;

    @Value("${app.preview.max-cache-bytes:2147483648}")
    private long maxCacheBytes;

    @Value("${app.preview.conversion-timeout-seconds:120}")
    private int conversionTimeoutSeconds;

    private Path cachePath;
    private HttpClient httpClient;
    private ExecutorService conversionExecutor;

    public FilePreviewService(FileArchiveService fileArchiveService) {
        this.fileArchiveService = fileArchiveService;
    }

    @PostConstruct
    public void init() {
        cachePath = Paths.get(cacheDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(cachePath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件预览缓存目录: " + cachePath, e);
        }
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, Math.max(1, conversionTimeoutSeconds))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        conversionExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "file-preview-converter");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        if (conversionExecutor != null) {
            conversionExecutor.shutdownNow();
        }
    }

    public boolean isPreviewable(String storedName) {
        String extension = extensionOf(storedName);
        return "pdf".equals(extension) || "ppt".equals(extension) || "pptx".equals(extension);
    }

    public synchronized PreviewStatus preparePreview(String storedName, boolean retry) {
        if (!isPreviewable(storedName)) {
            return new PreviewStatus(UNSUPPORTED, "该文件类型暂不支持在线预览");
        }
        if ("pdf".equals(extensionOf(storedName))) {
            return new PreviewStatus(READY, "PDF 可直接预览");
        }

        Path cached = previewCacheFile(storedName);
        if (isUsablePdf(cached)) {
            touch(cached);
            jobs.put(storedName, new PreviewJob(READY, "预览已生成", Instant.now()));
            return new PreviewStatus(READY, "预览已生成");
        }

        PreviewJob current = jobs.get(storedName);
        if (current != null) {
            if (PROCESSING.equals(current.status())) {
                return new PreviewStatus(PROCESSING, current.message());
            }
            if (FAILED.equals(current.status()) && !retry) {
                return new PreviewStatus(FAILED, current.message());
            }
        }

        PreviewJob processing = new PreviewJob(PROCESSING, "正在生成演示文稿预览…", Instant.now());
        jobs.put(storedName, processing);
        try {
            conversionExecutor.submit(() -> convertPresentation(storedName));
        } catch (RejectedExecutionException e) {
            PreviewJob failed = new PreviewJob(FAILED, "预览任务暂时无法提交，请稍后重试", Instant.now());
            jobs.put(storedName, failed);
            return new PreviewStatus(failed.status(), failed.message());
        }
        return new PreviewStatus(processing.status(), processing.message());
    }

    /** 返回可展示的 PDF；演示文稿尚未转换完成时返回 null。 */
    public Path resolvePreviewFile(String storedName) throws Exception {
        if (!isPreviewable(storedName)) {
            return null;
        }
        if ("pdf".equals(extensionOf(storedName))) {
            return fileArchiveService.resolveFile(storedName);
        }
        Path cached = previewCacheFile(storedName);
        if (isUsablePdf(cached)) {
            touch(cached);
            return cached;
        }
        return null;
    }

    private void convertPresentation(String storedName) {
        Path temporary = null;
        try {
            Path source = fileArchiveService.resolveFile(storedName);
            long sourceSize = Files.size(source);
            if (sourceSize <= 0) {
                throw new PreviewException("文件内容为空，无法生成预览");
            }
            if (sourceSize > maxSourceBytes) {
                throw new PreviewException("文件超过 " + formatMegabytes(maxSourceBytes) + "MB 的在线预览限制，请下载后查看");
            }

            Files.createDirectories(cachePath);
            temporary = Files.createTempFile(cachePath, "preview-", ".tmp");
            requestConversion(source, storedName, temporary);
            if (!isUsablePdf(temporary)) {
                throw new PreviewException("转换服务未返回有效的 PDF 文件");
            }

            Path target = previewCacheFile(storedName);
            moveAtomically(temporary, target);
            temporary = null;
            jobs.put(storedName, new PreviewJob(READY, "预览已生成", Instant.now()));
            evictCacheIfNeeded();
            log.info("演示文稿预览生成成功: {}", storedName);
        } catch (PreviewException e) {
            jobs.put(storedName, new PreviewJob(FAILED, e.getMessage(), Instant.now()));
            log.warn("演示文稿预览生成失败: {} ({})", storedName, e.getMessage());
        } catch (HttpTimeoutException e) {
            jobs.put(storedName, new PreviewJob(FAILED, "预览生成超时，请稍后重试或直接下载", Instant.now()));
            log.warn("演示文稿预览转换超时: {}", storedName);
        } catch (ConnectException e) {
            jobs.put(storedName, new PreviewJob(FAILED, "PPT 预览服务暂不可用，请稍后重试或直接下载", Instant.now()));
            log.warn("无法连接演示文稿转换服务: {}", converterUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobs.put(storedName, new PreviewJob(FAILED, "预览任务已取消，请重试", Instant.now()));
        } catch (Exception e) {
            jobs.put(storedName, new PreviewJob(FAILED, "预览生成失败，请重试或直接下载", Instant.now()));
            log.error("演示文稿预览生成异常: {}", storedName, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.debug("清理预览临时文件失败: {}", temporary, e);
                }
            }
        }
    }

    private void requestConversion(Path source, String storedName, Path destination)
            throws IOException, InterruptedException, PreviewException {
        String boundary = "----EmiePreview" + UUID.randomUUID().toString().replace("-", "");
        String multipartFileName = storedName.replaceAll("[^A-Za-z0-9._-]", "_");
        String mimeType = URLConnection.guessContentTypeFromName(storedName);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"" + multipartFileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";

        URI endpoint = URI.create(stripTrailingSlash(converterUrl) + "/forms/libreoffice/convert");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(1, conversionTimeoutSeconds)))
                .header("Accept", "application/pdf")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Gotenberg-Output-Filename", "preview")
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix.getBytes(StandardCharsets.UTF_8)),
                        HttpRequest.BodyPublishers.ofFile(source),
                        HttpRequest.BodyPublishers.ofByteArray(suffix.getBytes(StandardCharsets.UTF_8))))
                .build();

        HttpResponse<Path> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofFile(destination,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (response.statusCode() != 200) {
            throw new PreviewException("转换服务返回异常状态（" + response.statusCode() + "）");
        }
    }

    private Path previewCacheFile(String storedName) {
        Path resolved = cachePath.resolve(storedName + ".pdf").normalize();
        if (!resolved.startsWith(cachePath)) {
            throw new IllegalArgumentException("预览文件名非法");
        }
        return resolved;
    }

    private boolean isUsablePdf(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            if (Files.size(path) < 5) {
                return false;
            }
            byte[] header = new byte[5];
            try (var input = Files.newInputStream(path)) {
                if (input.read(header) != header.length) {
                    return false;
                }
            }
            return "%PDF-".equals(new String(header, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return false;
        }
    }

    private void evictCacheIfNeeded() {
        try (Stream<Path> stream = Files.list(cachePath)) {
            var files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .sorted(Comparator.comparingLong(this::lastModified))
                    .toList();
            long total = 0;
            for (Path file : files) {
                total += Files.size(file);
            }
            for (Path file : files) {
                if (total <= maxCacheBytes) {
                    break;
                }
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".pdf")) {
                    jobs.remove(fileName.substring(0, fileName.length() - 4));
                }
            }
        } catch (IOException e) {
            log.warn("清理文件预览缓存失败", e);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.now()));
        } catch (IOException e) {
            log.debug("刷新预览缓存访问时间失败: {}", path, e);
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:3000";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static long formatMegabytes(long bytes) {
        return Math.max(1, bytes / 1024 / 1024);
    }

    public record PreviewStatus(String status, String message) {}

    private record PreviewJob(String status, String message, Instant updatedAt) {}

    private static class PreviewException extends Exception {
        PreviewException(String message) {
            super(message);
        }
    }
}
