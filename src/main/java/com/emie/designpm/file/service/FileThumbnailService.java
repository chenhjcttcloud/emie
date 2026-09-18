package com.emie.designpm.file.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 生成并缓存图片缩略图，原图只在用户点击预览时读取。 */
@Service
public class FileThumbnailService {
    private static final Logger log = LoggerFactory.getLogger(FileThumbnailService.class);
    private static final int MAX_SIDE = 640;
    private static final int AI_MAX_SIDE = 560;
    private static final int THUMBNAIL_CONCURRENCY = resolveThumbnailConcurrency();
    private static final Semaphore THUMBNAIL_SLOTS = new Semaphore(THUMBNAIL_CONCURRENCY);
    private final FileArchiveService fileArchiveService;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private ExecutorService executor;

    public FileThumbnailService(FileArchiveService fileArchiveService) {
        this.fileArchiveService = fileArchiveService;
    }

    @PostConstruct
    void init() {
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "thumbnail-generator");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    /**
     * 缩略图生成（含 AI 文件的 PDF 渲染）是 CPU 密集操作。多个 AI 文件同时渲染会拖慢其余接口。
     * 默认一次只生成 1 个缩略图，并允许用环境变量按机器实际规格覆盖。
     */
    private static int resolveThumbnailConcurrency() {
        String configured = System.getProperty("app.thumbnail.concurrency", System.getenv("APP_THUMBNAIL_CONCURRENCY"));
        if (configured != null) {
            try {
                int value = Integer.parseInt(configured.trim());
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
                // 配置非法时回退到自动计算
            }
        }
        return 1;
    }

    public Path getOrCreate(String storedName, Path cacheRoot) throws IOException {
        Path source;
        try {
            source = fileArchiveService.resolveFile(storedName);
        } catch (Exception e) {
            throw new IOException("文件不存在", e);
        }
        Files.createDirectories(cacheRoot);
        String safeName = storedName.replaceAll("[^a-zA-Z0-9._-]", "_");
        boolean aiFile = storedName.toLowerCase(java.util.Locale.ROOT).endsWith(".ai");
        // AI 预览规格单独带版本，确保旧的高分辨率缓存自动失效。
        Path target = thumbnailPath(safeName, aiFile, cacheRoot);
        if (Files.exists(target)
                && Files.getLastModifiedTime(target).toMillis()
                        >= Files.getLastModifiedTime(source).toMillis()) {
            return target;
        }
        boolean acquired = false;
        try {
            // 批量打开任务详情/图档库时允许生成任务排队，避免前端收到 429/失败占位图。
            if (!THUMBNAIL_SLOTS.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new IOException("缩略图生成任务繁忙，请稍后重试");
            }
            acquired = true;
            if (Files.exists(target)
                    && Files.getLastModifiedTime(target).toMillis()
                            >= Files.getLastModifiedTime(source).toMillis()) {
                return target;
            }
            BufferedImage input = aiFile ? renderPdfCompatibleAi(source) : ImageIO.read(source.toFile());
            if (input == null) throw new IOException("无法读取图片");
            try {
                int maxSide = aiFile ? AI_MAX_SIDE : MAX_SIDE;
                double scale = Math.min(1d, (double) maxSide / Math.max(input.getWidth(), input.getHeight()));
                int width = Math.max(1, (int) Math.round(input.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(input.getHeight() * scale));
                BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                try {
                    Graphics2D graphics = output.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, width, height);
                        graphics.setRenderingHint(
                                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(input, 0, 0, width, height, null);
                    } finally {
                        graphics.dispose();
                    }
                    writePngAtomically(output, target);
                } finally {
                    output.flush();
                }
            } finally {
                input.flush();
            }
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("缩略图生成被中断", e);
        } finally {
            if (acquired) THUMBNAIL_SLOTS.release();
        }
    }

    public Optional<Path> cached(String storedName, Path cacheRoot) {
        boolean aiFile = storedName.toLowerCase(java.util.Locale.ROOT).endsWith(".ai");
        Path target = thumbnailPath(storedName.replaceAll("[^a-zA-Z0-9._-]", "_"), aiFile, cacheRoot);
        return Files.isRegularFile(target) ? Optional.of(target) : Optional.empty();
    }

    public void enqueue(String storedName, Path cacheRoot) {
        if (cached(storedName, cacheRoot).isPresent()) return;
        String key = cacheRoot.toAbsolutePath().normalize() + ":" + storedName;
        if (!queued.add(key)) return;
        try {
            executor.submit(() -> {
                try {
                    getOrCreate(storedName, cacheRoot);
                } catch (IOException e) {
                    log.warn("后台缩略图生成失败 storedName={}: {}", storedName, e.getMessage());
                } finally {
                    queued.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            queued.remove(key);
        }
    }

    public boolean isBusy() {
        return !queued.isEmpty();
    }

    private static Path thumbnailPath(String safeName, boolean aiFile, Path cacheRoot) {
        return cacheRoot
                .resolve(safeName + (aiFile ? ".ai-preview-v4.png" : ".png"))
                .normalize();
    }

    private BufferedImage renderPdfCompatibleAi(Path source) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            if (document.getNumberOfPages() == 0) throw new IOException("AI 文件没有可预览页面");
            return new PDFRenderer(document).renderImageWithDPI(0, 72);
        } catch (IOException e) {
            throw new IOException("AI 文件未包含 PDF 兼容预览", e);
        }
    }

    private void writePngAtomically(BufferedImage image, Path target) throws IOException {
        Path temp = target.resolveSibling(
                target.getFileName() + ".tmp-" + Thread.currentThread().getId());
        try {
            if (!ImageIO.write(image, "png", temp.toFile())) {
                throw new IOException("系统不支持 PNG 编码");
            }
            Files.move(
                    temp,
                    target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
