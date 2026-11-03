package com.emie.designpm.file.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileThumbnailServiceTest {
    @TempDir
    Path directory;

    @Test
    void hugePngIsSubsampledInActualSixtyFourMegabyteJvmAndOriginalIsUnchanged() throws Exception {
        Path source = directory.resolve("large.png");
        writeStreamingPng(source, 16_000, 12_000);
        byte[] originalHash = hash(source);
        Path cache = directory.resolve("cache");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-Djava.awt.headless=true",
                "-cp",
                classPath,
                LowHeapProbe.class.getName(),
                source.toString(),
                cache.toString());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "bounded thumbnail generation must finish");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("thumbnail=640x480"), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertArrayEquals(originalHash, hash(source));
        BufferedImage thumbnail = ImageIO.read(cache.resolve("large.png.png").toFile());
        try {
            assertEquals(640, thumbnail.getWidth());
            assertEquals(480, thumbnail.getHeight());
            Color pixel = new Color(thumbnail.getRGB(300, 200));
            assertTrue(pixel.getBlue() > 180 && pixel.getRed() < 50);
        } finally {
            thumbnail.flush();
        }
    }

    @Test
    void corruptRasterIsNotRequeuedUntilSourceChangesAndValidThumbnailIsCached() throws Exception {
        Path source = Files.writeString(directory.resolve("cover.png"), "broken image");
        Path cache = directory.resolve("cache");
        FileArchiveService archive = mock(FileArchiveService.class);
        when(archive.resolveFile("cover.png")).thenReturn(source);
        FileThumbnailService service = new FileThumbnailService(archive);
        assertThrows(IOException.class, () -> service.getOrCreate("cover.png", cache));
        assertTrue(service.failed("cover.png", cache));
        assertThrows(IOException.class, () -> service.getOrCreate("cover.png", cache));
        service.enqueue("cover.png", cache);
        assertFalse(service.isBusy());
        verify(archive, times(1)).resolveFile("cover.png");

        writeSmallPng(source);
        Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 1000));
        assertFalse(service.failed("cover.png", cache), "replacing the source clears retry suppression");
        Path result = service.getOrCreate("cover.png", cache);
        assertTrue(service.cached("cover.png", cache).isPresent());
        assertFalse(service.failed("cover.png", cache));
        Files.setLastModifiedTime(result, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        FileTime cachedAt = Files.getLastModifiedTime(result);
        assertEquals(result, service.getOrCreate("cover.png", cache));
        assertEquals(cachedAt, Files.getLastModifiedTime(result), "a fresh cache is not rewritten");
    }

    @Test
    void rasterFailureCooldownExpiresWithoutBlockingArchiveResolutionFailures() throws Exception {
        Path source = Files.writeString(directory.resolve("retry.png"), "broken image");
        Path cache = directory.resolve("cache");
        FileArchiveService archive = mock(FileArchiveService.class);
        when(archive.resolveFile("retry.png")).thenReturn(source);
        FileThumbnailService service = new FileThumbnailService(archive);
        assertThrows(IOException.class, () -> service.getOrCreate("retry.png", cache));
        Path marker = cache.resolve("retry.png.png.failed");
        Files.setLastModifiedTime(
                marker, FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(6)));
        assertFalse(service.failed("retry.png", cache));
        assertFalse(Files.exists(marker));
        assertThrows(IOException.class, () -> service.getOrCreate("retry.png", cache));
        verify(archive, times(2)).resolveFile("retry.png");

        Path restored = directory.resolve("restored.png");
        writeSmallPng(restored);
        when(archive.resolveFile("restored.png"))
                .thenThrow(new IOException("archive temporarily unavailable"))
                .thenReturn(restored);
        assertThrows(IOException.class, () -> service.getOrCreate("restored.png", cache));
        assertFalse(service.failed("restored.png", cache), "transient archive errors must remain retryable");
        assertTrue(Files.isRegularFile(service.getOrCreate("restored.png", cache)));
    }

    @Test
    void excessivePixelHeaderIsRejectedBeforeRasterAllocation() throws Exception {
        Path source = directory.resolve("bomb.png"), cache = directory.resolve("cache");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(source))) {
            output.writeLong(0x89504E470D0A1A0AL);
            chunk(output, "IHDR", header(50_000, 50_000));
            chunk(output, "IEND", new byte[0]);
        }
        FileArchiveService archive = mock(FileArchiveService.class);
        when(archive.resolveFile("bomb.png")).thenReturn(source);
        FileThumbnailService service = new FileThumbnailService(archive);
        IOException failure = assertThrows(IOException.class, () -> service.getOrCreate("bomb.png", cache));
        assertTrue(failure.getMessage().contains("分辨率"));
        assertTrue(service.failed("bomb.png", cache));
        assertTrue(service.cached("bomb.png", cache).isEmpty());
    }

    @Test
    void decoderRuntimeAndOutOfMemoryFailuresDisposeReaderAndSuppressRetries() throws Exception {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        for (boolean outOfMemory : new boolean[] {false, true}) {
            AtomicBoolean disposed = new AtomicBoolean();
            ImageReaderSpi provider = throwingReader(outOfMemory, disposed);
            registry.registerServiceProvider(provider);
            try {
                String name = outOfMemory ? "oom.png" : "runtime.png";
                Path source = Files.write(directory.resolve(name), new byte[] {'B', 'O', 'O', 'M'});
                FileArchiveService archive = mock(FileArchiveService.class);
                when(archive.resolveFile(name)).thenReturn(source);
                FileThumbnailService service = new FileThumbnailService(archive);
                Path cache = directory.resolve(name + "-cache");
                assertThrows(IOException.class, () -> service.getOrCreate(name, cache));
                assertTrue(disposed.get());
                assertTrue(service.failed(name, cache));
                service.enqueue(name, cache);
                verify(archive, times(1)).resolveFile(name);
            } finally {
                registry.deregisterServiceProvider(provider);
            }
        }
    }

    /** A full-size RGB decode needs 576MB; the child is deliberately limited to 64MB. */
    public static class LowHeapProbe {
        public static void main(String[] args) throws Exception {
            Path source = Path.of(args[0]);
            FileArchiveService archive = new FileArchiveService(null, null) {
                @Override
                public Path resolveFile(String name) {
                    return source;
                }
            };
            Path result = new FileThumbnailService(archive).getOrCreate("large.png", Path.of(args[1]));
            BufferedImage image = ImageIO.read(result.toFile());
            try {
                if (image.getWidth() != 640 || image.getHeight() != 480)
                    throw new IllegalStateException("Unexpected thumbnail dimensions");
                System.out.println("thumbnail=640x480");
            } finally {
                image.flush();
            }
        }
    }

    private static ImageReaderSpi throwingReader(boolean outOfMemory, AtomicBoolean disposed) {
        return new ImageReaderSpi() {
            {
                vendorName = "EMIE test";
                version = "1";
                names = new String[] {"failure-fixture"};
                inputTypes = new Class<?>[] {ImageInputStream.class};
            }

            @Override
            public boolean canDecodeInput(Object source) throws IOException {
                if (!(source instanceof ImageInputStream input)) return false;
                long position = input.getStreamPosition();
                try {
                    return input.length() != 0 && input.readInt() == 0x424F4F4D;
                } finally {
                    input.seek(position);
                }
            }

            @Override
            public ImageReader createReaderInstance(Object extension) {
                return new ImageReader(this) {
                    @Override
                    public int getNumImages(boolean allowSearch) {
                        return 1;
                    }

                    @Override
                    public int getWidth(int imageIndex) {
                        return 100;
                    }

                    @Override
                    public int getHeight(int imageIndex) {
                        return 100;
                    }

                    @Override
                    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) {
                        return List.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB))
                                .iterator();
                    }

                    @Override
                    public IIOMetadata getStreamMetadata() {
                        return null;
                    }

                    @Override
                    public IIOMetadata getImageMetadata(int imageIndex) {
                        return null;
                    }

                    @Override
                    public BufferedImage read(int imageIndex, ImageReadParam parameters) {
                        if (outOfMemory) throw new OutOfMemoryError("synthetic decoder failure");
                        throw new IllegalStateException("synthetic decoder failure");
                    }

                    @Override
                    public void dispose() {
                        disposed.set(true);
                        super.dispose();
                    }
                };
            }

            @Override
            public String getDescription(Locale locale) {
                return "Bounded thumbnail failure fixture";
            }
        };
    }

    private static void writeSmallPng(Path target) throws IOException {
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        try {
            assertTrue(ImageIO.write(image, "png", target.toFile()));
        } finally {
            image.flush();
        }
    }

    private static byte[] hash(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(source)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static byte[] header(int width, int height) {
        return ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
    }

    private static void writeStreamingPng(Path target, int width, int height) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(target))) {
            output.writeLong(0x89504E470D0A1A0AL);
            chunk(output, "IHDR", header(width, height));
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(new PngChunks(output), deflater)) {
                byte[] row = new byte[width * 3];
                for (int x = 0; x < width; x++) {
                    row[x * 3] = 31;
                    row[x * 3 + 1] = 95;
                    row[x * 3 + 2] = (byte) 191;
                }
                for (int y = 0; y < height; y++) {
                    compressed.write(0);
                    compressed.write(row);
                }
            } finally {
                deflater.end();
            }
            chunk(output, "IEND", new byte[0]);
        }
    }

    private static void chunk(DataOutputStream output, String name, byte[] payload) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(payload.length);
        output.write(type);
        output.write(payload);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(payload);
        output.writeInt((int) crc.getValue());
    }

    private static final class PngChunks extends OutputStream {
        private final DataOutputStream output;
        private final byte[] buffer = new byte[65536];
        private int size;

        PngChunks(DataOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) throws IOException {
            buffer[size++] = (byte) value;
            if (size == buffer.length) flushChunk();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int count = Math.min(buffer.length - size, length);
                System.arraycopy(bytes, offset, buffer, size, count);
                size += count;
                offset += count;
                length -= count;
                if (size == buffer.length) flushChunk();
            }
        }

        private void flushChunk() throws IOException {
            if (size == 0) return;
            chunk(output, "IDAT", java.util.Arrays.copyOf(buffer, size));
            size = 0;
        }

        @Override
        public void close() throws IOException {
            flushChunk();
        }
    }
}
