package com.emie.designpm.recommendation.service;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.file.service.FileThumbnailService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VisualInputReader {
    static final long MAX_BYTES = 15L * 1024 * 1024;
    private final FileArchiveService archive;
    private final FileThumbnailService thumbnails;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir = "./uploads";

    private final Map<String, ContentStamp> stamps = Collections.synchronizedMap(new LinkedHashMap<>(128, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ContentStamp> entry) {
            return size() > 1024;
        }
    });

    private record ContentStamp(long size, FileTime modified, Object fileKey, String hash) {}

    private record SelectedInput(Path path, String decoderName, String kind) {
        String key() {
            return kind + "|" + path;
        }
    }

    public VisualInputReader(FileArchiveService archive, FileThumbnailService thumbnails) {
        this.archive = archive;
        this.thumbnails = thumbnails;
    }

    boolean supported(String name) {
        return name != null
                && name.matches("(?i)[a-z0-9._-]+\\.(jpg|jpeg|png|gif|bmp|webp|ai|pdf)")
                && (!name.toLowerCase(Locale.ROOT).endsWith(".webp")
                        || ImageIO.getImageReadersBySuffix("webp").hasNext());
    }

    /** No archive restoration, content reads or network IO on recommendation HTTP requests. */
    String metadataFingerprint(FileRecord record, String modelFingerprint) throws Exception {
        SelectedInput selected = select(record, false);
        String localStamp = "";
        if (Files.isRegularFile(selected.path(), LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = attributes(selected.path());
            localStamp = attributes.size() + ":" + attributes.lastModifiedTime() + ":" + attributes.fileKey();
        }
        return VisualAiSettings.digest(record.getStoredName() + "|" + record.getFileSize() + "|" + record.getCreatedAt()
                + "|" + selected.key() + "|" + localStamp + "|" + modelFingerprint);
    }

    Path source(FileRecord record) throws Exception {
        return select(record, true).path();
    }

    private SelectedInput select(FileRecord record, boolean restoreOriginal) throws Exception {
        String name = record.getStoredName();
        if (!supported(name)) throw new IllegalArgumentException("该文件格式暂不支持图像分析");
        Path root = Path.of(uploadDir).toAbsolutePath().normalize();
        // The configured upload directory may itself be a mount/symlink; its cache may not escape it.
        if (Files.isDirectory(root)) root = root.toRealPath();
        Path cacheRoot = root.resolve("thumbnail-cache");
        Optional<Path> cached = thumbnails.cached(name, cacheRoot);
        if (cached.isPresent()) {
            Path thumbnail = cached.get().toAbsolutePath().normalize();
            String expectedName = name + (name.toLowerCase(Locale.ROOT).endsWith(".ai") ? ".ai-page-1.png" : ".png");
            if (!Objects.equals(thumbnail.getParent(), cacheRoot)
                    || !thumbnail.getFileName().toString().equals(expectedName)
                    || Files.isSymbolicLink(cacheRoot)
                    || Files.isSymbolicLink(thumbnail)) throw new IllegalArgumentException("缓存缩略图路径无效");
            validateSize(thumbnail);
            return new SelectedInput(thumbnail, expectedName, "thumbnail-v1");
        }
        if (record.getFileSize() == null || record.getFileSize() < 0 || record.getFileSize() > MAX_BYTES)
            throw new IllegalArgumentException("无可用缩略图，原文件分析仅支持15MB以内的文件");
        Path original = restoreOriginal ? archive.resolveFile(name) : root.resolve(name);
        original = original.toAbsolutePath().normalize();
        if (restoreOriginal) {
            validateSize(original);
            original = original.toRealPath();
        }
        return new SelectedInput(original, name, "original-v1");
    }

    private BasicFileAttributes attributes(Path path) throws Exception {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private void validateSize(Path path) throws Exception {
        BasicFileAttributes attributes = attributes(path);
        if (!attributes.isRegularFile() || attributes.size() <= 0 || attributes.size() > MAX_BYTES)
            throw new IllegalArgumentException("分析图片不存在、为空或超过15MB");
    }

    String fingerprint(FileRecord record, String modelFingerprint) throws Exception {
        SelectedInput selected = select(record, true);
        BasicFileAttributes attributes = attributes(selected.path());
        ContentStamp prior = stamps.get(selected.key());
        String hash;
        if (prior != null
                && prior.size() == attributes.size()
                && prior.modified().equals(attributes.lastModifiedTime())
                && Objects.equals(prior.fileKey(), attributes.fileKey())) hash = prior.hash();
        else {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(selected.path(), LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[16384];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_BYTES) throw new IllegalArgumentException("图片超过15MB");
                    digest.update(buffer, 0, read);
                }
            }
            hash = HexFormat.of().formatHex(digest.digest());
            stamps.put(
                    selected.key(),
                    new ContentStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey(), hash));
        }
        return VisualAiSettings.digest(selected.key() + "|" + hash + "|" + modelFingerprint);
    }

    public List<String> imageData(FileRecord record) throws Exception {
        SelectedInput selected = select(record, true);
        // Cached PNGs must use their own format, even when the source name ends in .ai or .pdf.
        // Parsing and decompression still run outside the web application's heap.
        return VisualImageWorker.render(selected.path(), selected.decoderName());
    }
}
