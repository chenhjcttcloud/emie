package com.emie.designpm.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 生成并缓存图片缩略图，原图只在用户点击预览时读取。 */
@Service
public class FileThumbnailService {
    private static final int MAX_SIDE = 640;
    private static final int AI_MAX_SIDE = 1800;
    private static final Semaphore THUMBNAIL_SLOTS = new Semaphore(4);
    private final FileArchiveService fileArchiveService;

    public FileThumbnailService(FileArchiveService fileArchiveService) {
        this.fileArchiveService = fileArchiveService;
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
        // AI 预览规格单独带版本，确保旧的 96 DPI / 640px 缓存自动失效。
        Path target = cacheRoot.resolve(safeName + (aiFile ? ".ai-preview-v2.png" : ".png")).normalize();
        if (Files.exists(target) && Files.getLastModifiedTime(target).toMillis() >= Files.getLastModifiedTime(source).toMillis()) {
            return target;
        }
        boolean acquired = false;
        try {
            if (!THUMBNAIL_SLOTS.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new IOException("缩略图生成任务繁忙，请稍后重试");
            }
            acquired = true;
            if (Files.exists(target) && Files.getLastModifiedTime(target).toMillis() >= Files.getLastModifiedTime(source).toMillis()) {
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
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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

    private BufferedImage renderPdfCompatibleAi(Path source) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            if (document.getNumberOfPages() == 0) throw new IOException("AI 文件没有可预览页面");
            return new PDFRenderer(document).renderImageWithDPI(0, 144);
        } catch (IOException e) {
            throw new IOException("AI 文件未包含 PDF 兼容预览", e);
        }
    }

    private void writePngAtomically(BufferedImage image, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().getId());
        try {
            if (!ImageIO.write(image, "png", temp.toFile())) {
                throw new IOException("系统不支持 PNG 编码");
            }
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
