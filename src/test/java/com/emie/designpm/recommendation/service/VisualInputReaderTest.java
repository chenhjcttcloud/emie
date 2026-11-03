package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.file.service.FileThumbnailService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class VisualInputReaderTest {
    @TempDir
    Path directory;

    private final FileArchiveService archive = mock(FileArchiveService.class);
    private final FileThumbnailService thumbnails = spy(new FileThumbnailService(archive));
    private final VisualInputReader reader = new VisualInputReader(archive, thumbnails);

    @BeforeEach
    void configureUploadDirectory() {
        ReflectionTestUtils.setField(reader, "uploadDir", directory.toString());
    }

    @Test
    void resizesRealPngAndEncodesBoundedJpeg() throws Exception {
        Path source = directory.resolve("wide.png");
        BufferedImage original = new BufferedImage(2400, 800, BufferedImage.TYPE_INT_ARGB);
        try {
            var graphics = original.createGraphics();
            try {
                graphics.setColor(Color.RED);
                graphics.fillRect(0, 0, 2400, 800);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(original, "png", source.toFile());
        } finally {
            original.flush();
        }
        when(archive.resolveFile("wide.png")).thenReturn(source);
        var images = reader.imageData(record("wide.png", Files.size(source)));
        assertEquals(1, images.size());
        BufferedImage encoded = decode(images.getFirst());
        try {
            assertEquals(1600, encoded.getWidth());
            assertTrue(encoded.getHeight() >= 532 && encoded.getHeight() <= 534);
            assertTrue(new Color(encoded.getRGB(100, 100)).getRed() > 200);
        } finally {
            encoded.flush();
        }
    }

    @Test
    void rendersOnlyFirstTwoPagesOfPdfCompatibleIllustratorFile() throws Exception {
        Path source = directory.resolve("artwork.ai");
        try (PDDocument document = new PDDocument()) {
            for (Color color : new Color[] {Color.RED, Color.BLUE, Color.GREEN}) {
                PDPage page = new PDPage(new PDRectangle(300, 200));
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(color);
                    content.addRect(0, 0, 300, 200);
                    content.fill();
                }
            }
            document.save(source.toFile());
        }
        when(archive.resolveFile("artwork.ai")).thenReturn(source);
        var pages = reader.imageData(record("artwork.ai", Files.size(source)));
        assertEquals(2, pages.size());
        BufferedImage first = decode(pages.get(0)), second = decode(pages.get(1));
        try {
            assertTrue(new Color(first.getRGB(20, 20)).getRed() > 200);
            assertTrue(new Color(second.getRGB(20, 20)).getBlue() > 200);
            assertTrue(first.getWidth() <= 1600 && first.getHeight() <= 1600);
        } finally {
            first.flush();
            second.flush();
        }
    }

    @Test
    void rejectsOversizeUnsupportedAndPixelBombBeforeDecoding() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> reader.imageData(record("large.png", VisualInputReader.MAX_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> reader.imageData(record("source.psd", 100)));
        verifyNoInteractions(archive);
        Path source = directory.resolve("bomb.png");
        BufferedImage tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try {
            ImageIO.write(tiny, "png", source.toFile());
        } finally {
            tiny.flush();
        }
        byte[] png = Files.readAllBytes(source);
        ByteBuffer.wrap(png).putInt(16, 90000).putInt(20, 90000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        Files.write(source, png);
        when(archive.resolveFile("bomb.png")).thenReturn(source);
        var error =
                assertThrows(IllegalArgumentException.class, () -> reader.imageData(record("bomb.png", png.length)));
        assertTrue(error.getMessage().contains("分辨率"));
    }

    @Test
    void analyzesCachedPngForLargeIllustratorWithoutOpeningOriginal() throws Exception {
        Path cached = thumbnail("large.ai", Color.BLUE);
        FileRecord record = record("large.ai", 450L * 1024 * 1024);
        assertFalse(reader.metadataFingerprint(record, "model").isBlank());
        String content = reader.fingerprint(record, "model");
        assertEquals(content, reader.fingerprint(record, "model"));
        var images = reader.imageData(record);
        assertEquals(1, images.size());
        BufferedImage decoded = decode(images.getFirst());
        try {
            assertEquals(64, decoded.getWidth());
            assertTrue(new Color(decoded.getRGB(10, 10)).getBlue() > 200);
        } finally {
            decoded.flush();
        }
        assertEquals(cached.toRealPath(), reader.source(record));
        verifyNoInteractions(archive);
        verify(thumbnails, never()).getOrCreate(anyString(), any());
        verify(thumbnails, never()).enqueue(anyString(), any());
    }

    @Test
    void missingThumbnailKeepsOriginalSizeLimitWithoutRestoringArchive() {
        FileRecord large = record("large.ai", VisualInputReader.MAX_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> reader.metadataFingerprint(large, "model"));
        assertThrows(IllegalArgumentException.class, () -> reader.fingerprint(large, "model"));
        assertThrows(IllegalArgumentException.class, () -> reader.imageData(large));
        verifyNoInteractions(archive);
    }

    @Test
    void thumbnailAppearanceAndReplacementInvalidateInputFingerprints() throws Exception {
        Path original = directory.resolve("cover.png");
        writePng(original, Color.RED);
        FileRecord record = record("cover.png", Files.size(original));
        when(archive.resolveFile("cover.png")).thenReturn(original);
        String originalMetadata = reader.metadataFingerprint(record, "model");
        String originalContent = reader.fingerprint(record, "model");
        Path cached = thumbnail("cover.png", Color.BLUE);
        String cachedMetadata = reader.metadataFingerprint(record, "model");
        String cachedContent = reader.fingerprint(record, "model");
        assertNotEquals(originalMetadata, cachedMetadata);
        assertNotEquals(originalContent, cachedContent);
        writePng(cached, Color.GREEN);
        Files.setLastModifiedTime(cached, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertNotEquals(cachedMetadata, reader.metadataFingerprint(record, "model"));
        assertNotEquals(cachedContent, reader.fingerprint(record, "model"));
        verify(archive, times(1)).resolveFile("cover.png");
    }

    @Test
    void rejectsInvalidOversizedAndSymbolicLinkThumbnailsWithoutOriginalFallback() throws Exception {
        FileRecord record = record("large.ai", VisualInputReader.MAX_BYTES + 1);
        Path cached = thumbnail("large.ai", Color.BLUE);
        Files.writeString(cached, "not an image");
        assertThrows(IllegalArgumentException.class, () -> reader.imageData(record));
        Files.write(cached, new byte[(int) VisualInputReader.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> reader.metadataFingerprint(record, "model"));
        assertThrows(IllegalArgumentException.class, () -> reader.imageData(record));
        Files.delete(cached);
        Path outside = directory.resolve("outside.png");
        writePng(outside, Color.BLUE);
        Files.createSymbolicLink(cached, outside);
        assertThrows(IllegalArgumentException.class, () -> reader.metadataFingerprint(record, "model"));
        assertThrows(IllegalArgumentException.class, () -> reader.imageData(record));
        verifyNoInteractions(archive);
    }

    @Test
    void rejectsEscapedCachePathsAndCacheDirectoryLinks() throws Exception {
        FileRecord record = record("large.ai", VisualInputReader.MAX_BYTES + 1);
        Path outside = directory.resolve("outside.png");
        writePng(outside, Color.BLUE);
        doReturn(Optional.of(outside)).when(thumbnails).cached(eq("large.ai"), any());
        assertThrows(IllegalArgumentException.class, () -> reader.metadataFingerprint(record, "model"));
        doCallRealMethod().when(thumbnails).cached(eq("large.ai"), any());
        Path other = Files.createDirectory(directory.resolve("other-cache"));
        writePng(other.resolve("large.ai.ai-page-1.png"), Color.BLUE);
        Files.createSymbolicLink(directory.resolve("thumbnail-cache"), other);
        assertThrows(IllegalArgumentException.class, () -> reader.metadataFingerprint(record, "model"));
        verifyNoInteractions(archive);
    }

    private Path thumbnail(String name, Color color) throws Exception {
        Path cache = Files.createDirectories(directory.resolve("thumbnail-cache"));
        Path target = cache.resolve(name + (name.endsWith(".ai") ? ".ai-page-1.png" : ".png"));
        writePng(target, color);
        return target;
    }

    private void writePng(Path target, Color color) throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        try {
            var graphics = image.createGraphics();
            try {
                graphics.setColor(color);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", target.toFile());
        } finally {
            image.flush();
        }
    }

    private FileRecord record(String name, long size) {
        return FileRecord.builder().storedName(name).fileSize(size).build();
    }

    private BufferedImage decode(String data) throws Exception {
        assertTrue(data.startsWith("data:image/jpeg;base64,"));
        byte[] bytes = Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1));
        assertEquals((byte) 0xff, bytes[0]);
        assertEquals((byte) 0xd8, bytes[1]);
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
