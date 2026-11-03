package com.emie.designpm.file.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 生成并缓存图片缩略图，原图只在用户点击预览时读取。 */
@Service
public class FileThumbnailService {
    private static final Logger log = LoggerFactory.getLogger(FileThumbnailService.class);
    private static final int MAX_SIDE = 640;
    private static final int MAX_DECODE_SIDE = 1280;
    private static final int MAX_SOURCE_SIDE = 50_000;
    private static final long MAX_SOURCE_PIXELS = 250_000_000L;
    private static final long RASTER_RETRY_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);
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
        boolean aiFile = storedName.toLowerCase(java.util.Locale.ROOT).endsWith(".ai");
        String safeName = storedName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = thumbnailPath(safeName, aiFile, cacheRoot);
        if (aiFile && Files.isRegularFile(target)) return target;
        if (aiFile && failed(storedName, cacheRoot)) throw new IOException("AI 缩略图生成失败，更新文件后可重试");
        if (!aiFile && failed(storedName, cacheRoot)) throw new IOException("图片缩略图生成失败，稍后或更新文件后可重试");
        Path source;
        try {
            source = fileArchiveService.resolveFile(storedName);
        } catch (Exception e) {
            throw new IOException("文件不存在", e);
        }
        Files.createDirectories(cacheRoot);
        if (!aiFile
                && Files.exists(target)
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
            if (aiFile && Files.isRegularFile(target)) return target;
            if (!aiFile
                    && Files.exists(target)
                    && Files.getLastModifiedTime(target).toMillis()
                            >= Files.getLastModifiedTime(source).toMillis()) {
                return target;
            }
            if (!aiFile && failed(storedName, cacheRoot)) throw new IOException("图片缩略图生成失败，稍后或更新文件后可重试");
            if (aiFile) {
                try {
                    AiThumbnailRenderer.generate(source, target);
                    Files.deleteIfExists(failureMarker(target));
                } catch (IOException e) {
                    if (!(e instanceof AiThumbnailRenderer.InsufficientMemoryException)) {
                        Files.writeString(failureMarker(target), e.getMessage());
                    }
                    throw e;
                }
            } else {
                try {
                    writeRasterThumbnail(source, target);
                    Files.deleteIfExists(failureMarker(target));
                } catch (IOException | RuntimeException | OutOfMemoryError failure) {
                    rememberRasterFailure(source, target);
                    if (failure instanceof IOException io) throw io;
                    throw new IOException("图片缩略图生成失败，已暂缓重试", failure);
                }
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

    public boolean failed(String storedName, Path cacheRoot) {
        boolean aiFile = storedName.toLowerCase(java.util.Locale.ROOT).endsWith(".ai");
        Path target = thumbnailPath(storedName.replaceAll("[^a-zA-Z0-9._-]", "_"), aiFile, cacheRoot);
        Path marker = failureMarker(target);
        if (aiFile) return Files.isRegularFile(marker);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            BasicFileAttributes marked = Files.readAttributes(marker, BasicFileAttributes.class);
            long age = System.currentTimeMillis() - marked.lastModifiedTime().toMillis();
            if (marked.size() <= 4096 && age >= 0 && age < RASTER_RETRY_DELAY_MILLIS) {
                Properties value = new Properties();
                try (var input = Files.newBufferedReader(marker)) {
                    value.load(input);
                }
                Path source = Path.of(java.net.URI.create(value.getProperty("source", "")));
                BasicFileAttributes current = Files.readAttributes(source, BasicFileAttributes.class);
                if (current.isRegularFile()
                        && Long.toString(current.size()).equals(value.getProperty("size"))
                        && current.lastModifiedTime().toString().equals(value.getProperty("modified"))) return true;
            }
        } catch (IOException | RuntimeException ignored) {
            // A removed/replaced local source may be restored from the archive on the next attempt.
        }
        try {
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
            // An unreadable marker must not permanently hide a recoverable file.
        }
        return false;
    }

    private void rememberRasterFailure(Path source, Path target) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) return;
            Properties value = new Properties();
            value.setProperty(
                    "source", source.toAbsolutePath().normalize().toUri().toString());
            value.setProperty("size", Long.toString(attributes.size()));
            value.setProperty("modified", attributes.lastModifiedTime().toString());
            try (var writer = Files.newBufferedWriter(failureMarker(target))) {
                value.store(writer, "Raster thumbnail retry cooldown");
            }
        } catch (IOException | RuntimeException ignored) {
            // Archive resolution errors are handled before this method and do not poison retry state.
        }
    }

    private void writeRasterThumbnail(Path source, Path target) throws IOException {
        try (var stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) throw new IOException("无法读取图片");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("无法读取图片格式");
            ImageReader reader = readers.next();
            BufferedImage image = null;
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0
                        || height <= 0
                        || width > MAX_SOURCE_SIDE
                        || height > MAX_SOURCE_SIDE
                        || (long) width * height > MAX_SOURCE_PIXELS) throw new IOException("图片分辨率超过缩略图限制");
                int step = Math.max(1, (Math.max(width, height) + MAX_DECODE_SIDE - 1) / MAX_DECODE_SIDE);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(step, step, 0, 0);
                image = reader.read(0, parameters);
                if (image == null || image.getWidth() > MAX_DECODE_SIDE || image.getHeight() > MAX_DECODE_SIDE)
                    throw new IOException("图片解码未遵循缩略图尺寸限制");
                writeThumbnail(image, target, MAX_SIDE);
            } finally {
                if (image != null) image.flush();
                reader.dispose();
            }
        }
    }

    public java.util.List<Path> getOrCreateAiPages(String storedName, Path cacheRoot) throws IOException {
        if (!storedName.toLowerCase(java.util.Locale.ROOT).endsWith(".ai")) {
            throw new IOException("仅支持 AI 文件多页预览");
        }
        Files.createDirectories(cacheRoot);
        String safeName = storedName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path first = thumbnailPath(safeName, true, cacheRoot);
        if (!Files.isRegularFile(first)) {
            getOrCreate(storedName, cacheRoot);
        }
        try (var files = Files.list(cacheRoot)) {
            return files.filter(path -> path.getFileName().toString().startsWith(safeName + ".ai-page-"))
                    .filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparingInt(FileThumbnailService::aiPageNumber))
                    .toList();
        }
    }

    private static int aiPageNumber(Path path) {
        String name = path.getFileName().toString();
        int start = name.lastIndexOf(".ai-page-") + 9;
        int end = name.lastIndexOf(".png");
        try {
            return Integer.parseInt(name.substring(start, end));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    public void enqueue(String storedName, Path cacheRoot) {
        if (cached(storedName, cacheRoot).isPresent() || failed(storedName, cacheRoot)) return;
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
                .resolve(safeName + (aiFile ? ".ai-page-1.png" : ".png"))
                .normalize();
    }

    private static Path failureMarker(Path target) {
        return target.resolveSibling(target.getFileName() + ".failed");
    }

    private void writeThumbnail(BufferedImage input, Path target, int maxSide) throws IOException {
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
