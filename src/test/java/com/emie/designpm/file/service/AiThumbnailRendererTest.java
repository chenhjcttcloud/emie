package com.emie.designpm.file.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiThumbnailRendererTest {
    @TempDir
    Path tempDir;

    @Test
    void skipsRenderingWhenHostOrContainerMemoryIsLow() throws Exception {
        Path meminfo = Files.writeString(tempDir.resolve("meminfo"), "MemAvailable: 2000000 kB\n");
        Path group = Files.createDirectory(tempDir.resolve("cgroup"));
        Files.writeString(group.resolve("memory.max"), "805306368\n");
        Files.writeString(group.resolve("memory.current"), "100000000\n");
        assertFalse(AiThumbnailRenderer.hasMemoryForChild(meminfo, group));
        Files.writeString(group.resolve("memory.max"), "max\n");
        Files.writeString(meminfo, "MemAvailable: 500000 kB\n");
        assertFalse(AiThumbnailRenderer.hasMemoryForChild(meminfo, group));
        Files.writeString(meminfo, "MemAvailable: 2000000 kB\n");
        assertTrue(AiThumbnailRenderer.hasMemoryForChild(meminfo, group));
    }

    @Test
    void rendersPdfCompatibleAiInSeparateProcess() throws Exception {
        Path source = tempDir.resolve("artwork.ai");
        Path target = tempDir.resolve("artwork.ai-page-1.png");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(source.toFile());
        }

        AiThumbnailRenderer.generate(source, target);

        assertTrue(Files.isRegularFile(target));
        assertNotNull(ImageIO.read(target.toFile()));
    }
}
