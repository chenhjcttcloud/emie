package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.file.service.FileArchiveService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualInputReaderTest {
    @TempDir
    Path directory;

    private final FileArchiveService archive = mock(FileArchiveService.class);
    private final VisualInputReader reader = new VisualInputReader(archive);

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
