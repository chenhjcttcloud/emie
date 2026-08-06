package com.emie.designpm;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.service.FileThumbnailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        Path thumbnail = new FileThumbnailService(archiveService)
                .getOrCreate(storedName, uploadDir.resolve("thumbnail-cache"));

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
        Files.setLastModifiedTime(thumbnail, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(source).toMillis() + 5000));
        when(records.findByStoredName(storedName)).thenReturn(Optional.of(FileRecord.builder()
                .storedName(storedName).storageTier("local").build()));

        assertEquals(thumbnail, new FileThumbnailService(archiveService).getOrCreate(storedName, cache));
        assertEquals("cached thumbnail", Files.readString(thumbnail));
    }
}
