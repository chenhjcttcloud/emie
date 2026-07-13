package com.emie.designpm;

import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.service.FilePreviewService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilePreviewServiceTest {

    @TempDir
    Path tempDir;

    private FilePreviewService service;
    private HttpServer converter;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (converter != null) {
            converter.stop(0);
        }
    }

    @Test
    void pdfUsesOriginalFileWithoutConversion() throws Exception {
        FileArchiveService archive = mock(FileArchiveService.class);
        Path pdf = Files.writeString(tempDir.resolve("brief.pdf"), "%PDF-1.4\n%%EOF");
        when(archive.resolveFile("brief.pdf")).thenReturn(pdf);
        service = createService(archive, "http://127.0.0.1:1");

        FilePreviewService.PreviewStatus status = service.preparePreview("brief.pdf", false);

        assertEquals("ready", status.status());
        assertEquals(pdf, service.resolvePreviewFile("brief.pdf"));
        assertTrue(service.isPreviewable("slides.pptx"));
        assertTrue(service.isPreviewable("legacy.ppt"));
    }

    @Test
    void presentationIsConvertedOnceAndCachedAsPdf() throws Exception {
        byte[] generatedPdf = "%PDF-1.4\n1 0 obj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger conversionCount = new AtomicInteger();
        converter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        converter.createContext("/forms/libreoffice/convert", exchange -> {
            conversionCount.incrementAndGet();
            assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("multipart/form-data;"));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, generatedPdf.length);
            exchange.getResponseBody().write(generatedPdf);
            exchange.close();
        });
        converter.start();

        FileArchiveService archive = mock(FileArchiveService.class);
        Path source = Files.writeString(tempDir.resolve("slides.pptx"), "fake-presentation");
        when(archive.resolveFile("slides.pptx")).thenReturn(source);
        service = createService(archive, "http://127.0.0.1:" + converter.getAddress().getPort());

        assertEquals("processing", service.preparePreview("slides.pptx", false).status());
        FilePreviewService.PreviewStatus status = awaitTerminalStatus("slides.pptx");

        assertEquals("ready", status.status());
        Path preview = service.resolvePreviewFile("slides.pptx");
        assertNotNull(preview);
        assertTrue(Files.readString(preview).startsWith("%PDF-"));
        assertEquals("ready", service.preparePreview("slides.pptx", false).status());
        assertEquals(1, conversionCount.get());
    }

    private FilePreviewService createService(FileArchiveService archive, String converterUrl) {
        FilePreviewService result = new FilePreviewService(archive);
        ReflectionTestUtils.setField(result, "cacheDir", tempDir.resolve("preview-cache").toString());
        ReflectionTestUtils.setField(result, "converterUrl", converterUrl);
        ReflectionTestUtils.setField(result, "maxSourceBytes", 1024L * 1024L);
        ReflectionTestUtils.setField(result, "maxCacheBytes", 10L * 1024L * 1024L);
        ReflectionTestUtils.setField(result, "conversionTimeoutSeconds", 5);
        result.init();
        return result;
    }

    private FilePreviewService.PreviewStatus awaitTerminalStatus(String storedName) throws InterruptedException {
        FilePreviewService.PreviewStatus status = null;
        for (int i = 0; i < 100; i++) {
            status = service.preparePreview(storedName, false);
            if (!"processing".equals(status.status())) {
                return status;
            }
            Thread.sleep(20);
        }
        return status;
    }
}
