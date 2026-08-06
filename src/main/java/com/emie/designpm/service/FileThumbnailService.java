package com.emie.designpm.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 生成并缓存图片缩略图，原图只在用户点击预览时读取。 */
@Service
public class FileThumbnailService {
    private static final int MAX_SIDE = 480;
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
        Path target = cacheRoot.resolve(safeName + ".jpg").normalize();
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
            BufferedImage input = ImageIO.read(source.toFile());
            if (input == null) throw new IOException("无法读取图片");
            try {
                double scale = Math.min(1d, (double) MAX_SIDE / Math.max(input.getWidth(), input.getHeight()));
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
                    writeJpegAtomically(output, target);
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

    private void writeJpegAtomically(BufferedImage image, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().getId());
        try {
            writeJpeg(image, temp);
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void writeJpeg(BufferedImage image, Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IOException("系统不支持 JPEG 缩略图");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.78f);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
