package com.emie.designpm.imagelibrary.service;

import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.file.service.FileThumbnailService;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ImageLibraryThumbnailPrewarm {
    private final FileRecordRepository files;
    private final FileThumbnailService thumbnails;
    private final Path cacheRoot;
    private boolean complete;

    ImageLibraryThumbnailPrewarm(
            FileRecordRepository files,
            FileThumbnailService thumbnails,
            @Value("${app.upload.dir:./uploads}") String uploadDir) {
        this.files = files;
        this.thumbnails = thumbnails;
        this.cacheRoot = Path.of(uploadDir).toAbsolutePath().normalize().resolve("thumbnail-cache");
    }

    @Scheduled(initialDelay = 3_000, fixedDelay = 3_000)
    void prewarm() {
        if (complete || thumbnails.isBusy()) return;
        var next = files.findByTargetTypeOrderByCreatedAtDesc("image_library").stream()
                .map(record -> record.getStoredName())
                .filter(name -> name != null && name.toLowerCase().endsWith(".ai"))
                .filter(name -> thumbnails.cached(name, cacheRoot).isEmpty())
                .findFirst();
        if (next.isEmpty()) complete = true;
        else thumbnails.enqueue(next.get(), cacheRoot);
    }
}
