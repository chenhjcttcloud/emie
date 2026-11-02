package com.emie.designpm.recommendation.service;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.file.service.FileArchiveService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VisualInputReader {
    static final long MAX_BYTES = 15L * 1024 * 1024;
    private final FileArchiveService archive;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir = "./uploads";

    private final Map<String, ContentStamp> stamps = Collections.synchronizedMap(new LinkedHashMap<>(128, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ContentStamp> entry) {
            return size() > 1024;
        }
    });

    private record ContentStamp(long size, long modified, String hash) {}

    public VisualInputReader(FileArchiveService archive) {
        this.archive = archive;
    }

    boolean supported(String name) {
        return name != null
                && name.matches("(?i)[a-z0-9._-]+\\.(jpg|jpeg|png|gif|bmp|webp|ai|pdf)")
                && (!name.toLowerCase(Locale.ROOT).endsWith(".webp")
                        || ImageIO.getImageReadersBySuffix("webp").hasNext());
    }

    /** No archive restoration, content reads or network IO on recommendation HTTP requests. */
    String metadataFingerprint(FileRecord record, String modelFingerprint) throws Exception {
        if (!supported(record.getStoredName())) throw new IllegalArgumentException("该文件格式暂不支持图像分析");
        if (record.getFileSize() == null || record.getFileSize() > MAX_BYTES)
            throw new IllegalArgumentException("图片超过15MB");
        Path root = Path.of(uploadDir).toAbsolutePath().normalize();
        Path local = root.resolve(record.getStoredName()).normalize();
        String localStamp = "";
        if (local.startsWith(root) && Files.isRegularFile(local)) {
            localStamp =
                    Files.size(local) + ":" + Files.getLastModifiedTime(local).toMillis();
        }
        return VisualAiSettings.digest(record.getStoredName() + "|" + record.getFileSize() + "|" + record.getCreatedAt()
                + "|" + localStamp + "|" + modelFingerprint);
    }

    Path source(FileRecord record) throws Exception {
        if (!supported(record.getStoredName())) throw new IllegalArgumentException("该文件格式暂不支持图像分析");
        if (record.getFileSize() == null || record.getFileSize() > MAX_BYTES)
            throw new IllegalArgumentException("AI 图片分析仅支持15MB以内的文件");
        Path source = archive.resolveFile(record.getStoredName());
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_BYTES)
            throw new IllegalArgumentException("图片不存在或超过15MB");
        return source;
    }

    String fingerprint(FileRecord record, String modelFingerprint) throws Exception {
        Path source = source(record);
        long size = Files.size(source),
                modified = Files.getLastModifiedTime(source).toMillis();
        ContentStamp prior = stamps.get(record.getStoredName());
        String hash;
        if (prior != null && prior.size() == size && prior.modified() == modified) hash = prior.hash();
        else {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(source)) {
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
            stamps.put(record.getStoredName(), new ContentStamp(size, modified, hash));
        }
        return VisualAiSettings.digest(hash + "|" + modelFingerprint);
    }

    List<String> imageData(FileRecord record) throws Exception {
        // Parsing and decompression must not share the web application's small heap.
        return VisualImageWorker.render(source(record), record.getStoredName());
    }
}
