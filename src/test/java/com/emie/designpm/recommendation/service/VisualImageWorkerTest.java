package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualImageWorkerTest {
    @TempDir
    Path directory;

    @Test
    void separateProcessReturnsOnlyTwoBoundedPdfPagesAndCleansTemporaryFiles() throws Exception {
        Path source = pdf();
        Path workRoot = Files.createDirectory(directory.resolve("work"));
        List<String> images =
                VisualImageWorker.render(source, "artwork.pdf", workRoot, VisualImageWorker.TIMEOUT_MILLIS);
        assertEquals(2, images.size());
        assertTrue(images.stream().allMatch(image -> image.startsWith("data:image/jpeg;base64,")));
        assertTrue(images.stream().allMatch(image -> image.length() <= VisualImageWorker.MAX_JPEG_BYTES * 4L / 3 + 32));
        assertEmpty(workRoot);
    }

    @Test
    void invalidPdfFailsInChildAndLeavesNoPartialOutput() throws Exception {
        Path source = Files.writeString(directory.resolve("invalid.pdf"), "%PDF-1.7\nnot a valid document");
        Path workRoot = Files.createDirectory(directory.resolve("work"));
        assertThrows(
                IOException.class,
                () -> VisualImageWorker.render(source, "invalid.pdf", workRoot, VisualImageWorker.TIMEOUT_MILLIS));
        assertEmpty(workRoot);
    }

    @Test
    void compressedPdfExpansionCannotExhaustTheParentHeap() throws Exception {
        Path source = directory.resolve("compressed.pdf");
        // A sub-megabyte PDF may expand to hundreds of megabytes while parsing a single text token.
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);
            PDStream contents = new PDStream(document);
            try (var output = contents.createOutputStream(COSName.FLATE_DECODE)) {
                output.write('(');
                byte[] block = new byte[8192];
                java.util.Arrays.fill(block, (byte) 'a');
                for (int i = 0; i < 32768; i++) output.write(block);
                output.write(") Tj".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            page.setContents(contents);
            document.save(source.toFile());
        }
        assertTrue(Files.size(source) < VisualInputReader.MAX_BYTES);
        Path workRoot = Files.createDirectory(directory.resolve("work"));
        assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> assertThrows(
                        IOException.class, () -> VisualImageWorker.render(source, "compressed.pdf", workRoot, 20_000)));
        assertEmpty(workRoot);
    }

    @Test
    void hardTimeoutTerminatesActualChildAndCleansItsDirectory() throws Exception {
        Path source = pdf();
        Path workRoot = Files.createDirectory(directory.resolve("work"));
        IOException failure = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> assertThrows(
                        IOException.class, () -> VisualImageWorker.render(source, "artwork.pdf", workRoot, 1)));
        assertTrue(failure.getMessage().contains("超时"));
        assertEmpty(workRoot);
        assertTrue(
                ProcessHandle.current()
                        .descendants()
                        .noneMatch(process ->
                                process.info().commandLine().orElse("").contains(VisualImageWorker.class.getName())),
                "timed-out decoder must not remain alive");
    }

    @Test
    void refusesExcessiveOutputDimensionsAndLengthsBeforeAllocatingFrames() throws Exception {
        Path result = directory.resolve("result.bin");
        for (int[] invalid : new int[][] {{1601, 1, 4}, {1, 1601, 4}, {1, 1, Integer.MAX_VALUE}, {0, 1, 4}}) {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(result))) {
                output.writeInt(0x45564931);
                output.writeInt(1);
                for (int value : invalid) output.writeInt(value);
            }
            assertThrows(IOException.class, () -> VisualImageWorker.readResult(result));
        }
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(result))) {
            output.writeInt(0x45564931);
            output.writeInt(3);
        }
        assertThrows(IOException.class, () -> VisualImageWorker.readResult(result));
    }

    @Test
    void respectsHostAndCgroupMemoryHeadroom() throws Exception {
        Path meminfo = Files.writeString(directory.resolve("meminfo"), "MemAvailable: 2000000 kB\n");
        Path group = Files.createDirectory(directory.resolve("cgroup"));
        Files.writeString(group.resolve("memory.max"), "536870912\n");
        Files.writeString(group.resolve("memory.current"), "268435456\n");
        assertFalse(VisualImageWorker.hasMemoryForChild(meminfo, group));
        Files.writeString(group.resolve("memory.max"), "max\n");
        Files.writeString(meminfo, "MemAvailable: 200000 kB\n");
        assertFalse(VisualImageWorker.hasMemoryForChild(meminfo, group));
        Files.writeString(meminfo, "MemAvailable: 2000000 kB\n");
        assertTrue(VisualImageWorker.hasMemoryForChild(meminfo, group));
    }

    private Path pdf() throws Exception {
        Path source = directory.resolve("artwork.pdf");
        try (PDDocument document = new PDDocument()) {
            for (Color color : List.of(Color.RED, Color.BLUE, Color.GREEN)) {
                PDPage page = new PDPage(new PDRectangle(1000, 600));
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(color);
                    content.addRect(0, 0, 1000, 600);
                    content.fill();
                }
            }
            document.save(source.toFile());
        }
        return source;
    }

    private void assertEmpty(Path path) throws IOException {
        try (var files = Files.list(path)) {
            assertEquals(0, files.count(), "decoder scratch directory must be removed on every exit path");
        }
    }
}
