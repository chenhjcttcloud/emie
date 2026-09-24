package com.emie.designpm.performance.service;

import com.emie.designpm.file.service.FileArchiveService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 读取系统上传嘅交付图片，转成可以嵌入 Excel 嘅 JPEG。
 *
 * <p>长边上限 {@value #MAX_EDGE} 像素、质量 {@value #JPEG_QUALITY}：表格入面显示缩略尺寸，
 * 但图片本身保留足够分辨率，喺 Excel/WPS 放大睇依然清晰。已归档到 NAS 嘅文件会自动取回。
 */
@Service
public class PerformanceImageLoader {
    private static final Logger log = LoggerFactory.getLogger(PerformanceImageLoader.class);
    static final int MAX_EDGE = 1600;
    static final float JPEG_QUALITY = 0.88f;
    private static final String DOWNLOAD_PREFIX = "/api/files/download/";

    private final FileArchiveService files;

    public PerformanceImageLoader(FileArchiveService files) {
        this.files = files;
    }

    public record LoadedImage(byte[] jpeg, int width, int height) {}

    public Optional<LoadedImage> load(String url) {
        String storedName = storedName(url);
        if (storedName == null) return Optional.empty();
        try {
            Path path = files.resolveFile(storedName);
            BufferedImage source;
            try (var in = Files.newInputStream(path)) {
                source = ImageIO.read(in);
            }
            if (source == null) return Optional.empty();
            return Optional.of(encode(source));
        } catch (Exception e) {
            log.warn("绩效导出读取图片失败 {}: {}", storedName, e.getMessage());
            return Optional.empty();
        }
    }

    /** 只接受系统自己嘅下载地址，外部链接一律唔读。 */
    static String storedName(String url) {
        if (url == null) return null;
        int query = url.indexOf('?');
        String clean = query >= 0 ? url.substring(0, query) : url;
        int start = clean.indexOf(DOWNLOAD_PREFIX);
        if (start < 0) return null;
        String name = java.net.URLDecoder.decode(
                clean.substring(start + DOWNLOAD_PREFIX.length()), java.nio.charset.StandardCharsets.UTF_8);
        if (name.isBlank() || name.contains("..") || name.startsWith("/")) return null;
        return name;
    }

    static LoadedImage encode(BufferedImage source) throws java.io.IOException {
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 透明 PNG 铺白底，避免转 JPEG 后变黑
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return new LoadedImage(out.toByteArray(), width, height);
    }
}
