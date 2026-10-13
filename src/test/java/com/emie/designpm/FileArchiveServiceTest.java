package com.emie.designpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.file.service.FileThumbnailService;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class FileArchiveServiceTest {

    @TempDir
    Path uploadDir;

    @Test
    void resolveFilePrefersExistingLocalFileEvenWhenDatabaseMarksItArchived() throws Exception {
        FileRecordRepository records = mock(FileRecordRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        FileArchiveService service = new FileArchiveService(records, configs);
        ReflectionTestUtils.setField(service, "uploadDir", uploadDir.toString());
        service.init();

        String storedName = "synced-image.png";
        Path localFile = Files.writeString(uploadDir.resolve(storedName), "image");
        FileRecord archived = FileRecord.builder()
                .storedName(storedName)
                .storageTier("archived")
                .build();
        when(records.findByStoredName(storedName)).thenReturn(Optional.of(archived));

        assertEquals(localFile, service.resolveFile(storedName));
    }

    @Test
    void thumbnailCanBeGeneratedForLocallySyncedArchivedImage() throws Exception {
        FileRecordRepository records = mock(FileRecordRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        FileArchiveService archiveService = new FileArchiveService(records, configs);
        ReflectionTestUtils.setField(archiveService, "uploadDir", uploadDir.toString());
        archiveService.init();

        String storedName = "synced-image.png";
        BufferedImage sourceImage = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(sourceImage, "png", uploadDir.resolve(storedName).toFile());
        sourceImage.flush();
        FileRecord archived = FileRecord.builder()
                .storedName(storedName)
                .storageTier("archived")
                .build();
        when(records.findByStoredName(storedName)).thenReturn(Optional.of(archived));

        Path thumbnail =
                new FileThumbnailService(archiveService).getOrCreate(storedName, uploadDir.resolve("thumbnail-cache"));

        assertTrue(Files.exists(thumbnail));
        BufferedImage thumbnailImage = ImageIO.read(thumbnail.toFile());
        assertEquals(640, thumbnailImage.getWidth());
        assertEquals(320, thumbnailImage.getHeight());
        thumbnailImage.flush();
    }

    @Test
    void existingFreshThumbnailIsReusedWithoutDecodingSourceAgain() throws Exception {
        FileRecordRepository records = mock(FileRecordRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        FileArchiveService archiveService = new FileArchiveService(records, configs);
        ReflectionTestUtils.setField(archiveService, "uploadDir", uploadDir.toString());
        archiveService.init();

        String storedName = "cached-image.png";
        Path source = uploadDir.resolve(storedName);
        Files.writeString(source, "not a decodable image");
        Path cache = uploadDir.resolve("thumbnail-cache");
        Files.createDirectories(cache);
        Path thumbnail = cache.resolve(storedName + ".png");
        Files.writeString(thumbnail, "cached thumbnail");
        Files.setLastModifiedTime(
                thumbnail,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(source).toMillis() + 5000));
        when(records.findByStoredName(storedName))
                .thenReturn(Optional.of(FileRecord.builder()
                        .storedName(storedName)
                        .storageTier("local")
                        .build()));

        assertEquals(thumbnail, new FileThumbnailService(archiveService).getOrCreate(storedName, cache));
        assertEquals("cached thumbnail", Files.readString(thumbnail));
    }

    @Test
    void failedAiThumbnailIsNotRetriedUntilStoredNameChanges() throws Exception {
        FileArchiveService archiveService = mock(FileArchiveService.class);
        Path aiFile = Files.writeString(uploadDir.resolve("large.ai"), "AI source");
        when(archiveService.resolveFile("large.ai")).thenReturn(aiFile);
        FileThumbnailService thumbnails = new FileThumbnailService(archiveService);
        Path cache = uploadDir.resolve("thumbnail-cache");

        assertThrows(IOException.class, () -> thumbnails.getOrCreate("large.ai", cache));
        assertTrue(thumbnails.failed("large.ai", cache));
        IOException repeated = assertThrows(IOException.class, () -> thumbnails.getOrCreate("large.ai", cache));
        assertTrue(repeated.getMessage().contains("更新文件后可重试"));
        Files.setLastModifiedTime(
                aiFile,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(aiFile).toMillis() + 2_000));
        assertTrue(thumbnails.failed("large.ai", cache));
        assertTrue(!thumbnails.failed("replacement.ai", cache));
        verify(archiveService, times(1)).resolveFile("large.ai");
    }
}
