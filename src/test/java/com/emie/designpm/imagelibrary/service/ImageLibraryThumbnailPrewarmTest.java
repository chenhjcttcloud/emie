package com.emie.designpm.imagelibrary.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.file.service.FileThumbnailService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageLibraryThumbnailPrewarmTest {
    @TempDir
    Path uploadDir;

    @Test
    void queuesOneUncachedAiFileAndSkipsFailedFiles() {
        FileRecordRepository files = mock(FileRecordRepository.class);
        FileThumbnailService thumbnails = mock(FileThumbnailService.class);
        Path cache = uploadDir.resolve("thumbnail-cache");
        when(files.findByTargetTypeOrderByCreatedAtDesc("image_library"))
                .thenReturn(List.of(
                        FileRecord.builder().storedName("failed.ai").build(),
                        FileRecord.builder().storedName("cover.AI").build(),
                        FileRecord.builder().storedName("cover.png").build()));
        when(thumbnails.failed("failed.ai", cache)).thenReturn(true);

        new ImageLibraryThumbnailPrewarm(files, thumbnails, uploadDir.toString()).prewarm();

        verify(thumbnails).enqueue("cover.AI", cache);
    }

    @Test
    void waitsForCurrentThumbnailWork() {
        FileRecordRepository files = mock(FileRecordRepository.class);
        FileThumbnailService thumbnails = mock(FileThumbnailService.class);
        when(thumbnails.isBusy()).thenReturn(true);

        new ImageLibraryThumbnailPrewarm(files, thumbnails, uploadDir.toString()).prewarm();

        verify(thumbnails).isBusy();
        verifyNoMoreInteractions(files, thumbnails);
    }
}
