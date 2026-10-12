package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesignerPerformanceExportServiceTest {
    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void queuesGenerationAndReportsCompletion() throws Exception {
        DesignerMonthlyReportService reports = mock(DesignerMonthlyReportService.class);
        when(reports.buildMonth(YearMonth.of(2026, 8))).thenReturn(List.of());
        List<Runnable> queued = new ArrayList<>();
        java.util.concurrent.Executor executor = queued::add;
        DesignerPerformanceExportService exports = new DesignerPerformanceExportService(
                reports, mock(PerformanceImageLoader.class), tempDir.toString(), false, executor);

        assertEquals(
                "RUNNING", exports.startGeneration(YearMonth.of(2026, 8), "管理员").status());
        assertEquals(0, exports.generationStatus(YearMonth.of(2026, 8)).progress());
        assertEquals(
                "RUNNING",
                exports.startGeneration(YearMonth.of(2026, 8), "另一个管理员").status());
        assertEquals(1, queued.size());
        queued.getFirst().run();

        assertEquals("READY", exports.generationStatus(YearMonth.of(2026, 8)).status());
        assertEquals(100, exports.generationStatus(YearMonth.of(2026, 8)).progress());
        assertTrue(exports.manifest(YearMonth.of(2026, 8)).isPresent());
        assertNotNull(exports.generationStatus(YearMonth.of(2026, 8)).generatedAt());
        var restarted = new DesignerPerformanceExportService(
                reports, mock(PerformanceImageLoader.class), tempDir.toString(), false, executor);
        assertEquals("READY", restarted.generationStatus(YearMonth.of(2026, 8)).status());
        assertNotNull(restarted.generationStatus(YearMonth.of(2026, 8)).generatedAt());
    }

    @Test
    void generationDoesNotBlockStartingOrRejoiningBackgroundJobs() throws Exception {
        var month = YearMonth.of(2026, 8);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var reports = mock(DesignerMonthlyReportService.class);
        when(reports.buildMonth(month)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("test timed out");
            return List.of();
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var exports = new DesignerPerformanceExportService(
                    reports, mock(PerformanceImageLoader.class), tempDir.toString(), false, executor);
            exports.startGeneration(month, "管理员");
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                    assertEquals(
                            "RUNNING", exports.startGeneration(month, "管理员").status());
                    assertEquals(
                            "RUNNING",
                            exports.startGeneration(month.minusMonths(1), "管理员").status());
                });
            } finally {
                release.countDown();
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("READY", exports.generationStatus(month).status());
            verify(reports, times(1)).buildMonth(month);
        }
    }

    @Test
    void scheduledPreGenerationUsesBackgroundStatusAndSkipsExistingSnapshot() {
        var month = YearMonth.now().minusMonths(1);
        var reports = mock(DesignerMonthlyReportService.class);
        when(reports.buildMonth(month)).thenReturn(List.of());
        List<Runnable> queued = new ArrayList<>();
        var exports = new DesignerPerformanceExportService(
                reports, mock(PerformanceImageLoader.class), tempDir.toString(), true, queued::add);
        exports.generatePreviousMonth();
        exports.generatePreviousMonth();
        assertEquals(1, queued.size());
        assertEquals("RUNNING", exports.generationStatus(month).status());
        queued.getFirst().run();
        exports.generatePreviousMonth();
        assertEquals(1, queued.size());
        assertEquals("READY", exports.generationStatus(month).status());
    }

    @Test
    void failedRegenerationKeepsPreviousSnapshotAvailable() {
        var month = YearMonth.of(2026, 8);
        var reports = mock(DesignerMonthlyReportService.class);
        when(reports.buildMonth(month)).thenReturn(List.of()).thenThrow(new IllegalStateException("unavailable"));
        List<Runnable> queued = new ArrayList<>();
        var exports = new DesignerPerformanceExportService(
                reports, mock(PerformanceImageLoader.class), tempDir.toString(), false, queued::add);
        exports.startGeneration(month, "管理员");
        queued.getFirst().run();
        String savedAt = exports.generationStatus(month).generatedAt();
        exports.startGeneration(month, "管理员");
        queued.get(1).run();
        assertEquals("FAILED", exports.generationStatus(month).status());
        assertEquals(savedAt, exports.generationStatus(month).generatedAt());
        assertTrue(exports.manifest(month).isPresent());
    }

    @Test
    void successfulReplacementRemovesBackupOnlyAfterPublishing() throws Exception {
        var target = java.nio.file.Files.createDirectory(tempDir.resolve("2026-08"));
        java.nio.file.Files.writeString(target.resolve("report.xlsx"), "old report");
        var staging = java.nio.file.Files.createDirectory(tempDir.resolve("staging"));
        java.nio.file.Files.writeString(staging.resolve("report.xlsx"), "new report");
        DesignerPerformanceExportService.replaceSnapshot(staging, target);
        assertEquals("new report", java.nio.file.Files.readString(target.resolve("report.xlsx")));
        try (var paths = java.nio.file.Files.list(tempDir)) {
            assertEquals(List.of(target), paths.toList());
        }
    }

    @Test
    void failedPublicationRestoresOldManifestAndWorkbook() throws Exception {
        var target = java.nio.file.Files.createDirectory(tempDir.resolve("2026-08"));
        java.nio.file.Files.writeString(target.resolve("report.xlsx"), "old report");
        java.nio.file.Files.writeString(target.resolve("manifest.json"), "old manifest");
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> DesignerPerformanceExportService.replaceSnapshot(tempDir.resolve("missing-staging"), target));
        assertEquals("old report", java.nio.file.Files.readString(target.resolve("report.xlsx")));
        assertEquals("old manifest", java.nio.file.Files.readString(target.resolve("manifest.json")));
        try (var paths = java.nio.file.Files.list(tempDir)) {
            assertEquals(List.of(target), paths.toList());
        }
    }

    @Test
    void failedRestoreRetainsBackupAndBothErrors() throws Exception {
        var target = java.nio.file.Files.createDirectory(tempDir.resolve("2026-08"));
        java.nio.file.Files.writeString(target.resolve("report.xlsx"), "old report");
        var staging = java.nio.file.Files.createDirectory(tempDir.resolve("staging"));
        var failure = new java.io.IOException("cannot publish or restore");
        try (var files =
                org.mockito.Mockito.mockStatic(java.nio.file.Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(() -> java.nio.file.Files.move(
                            org.mockito.ArgumentMatchers.any(java.nio.file.Path.class),
                            org.mockito.ArgumentMatchers.eq(target),
                            org.mockito.ArgumentMatchers.eq(java.nio.file.StandardCopyOption.ATOMIC_MOVE)))
                    .thenThrow(failure, new java.io.IOException("cannot restore"));
            var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class, () -> DesignerPerformanceExportService.replaceSnapshot(staging, target));
            assertEquals(failure, thrown);
            assertEquals(1, thrown.getSuppressed().length);
        }
        try (var paths = java.nio.file.Files.list(tempDir)) {
            var backup = paths.filter(path -> path.getFileName().toString().contains(".backup-"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("old report", java.nio.file.Files.readString(backup.resolve("report.xlsx")));
        }
    }

    @Test
    void zipDownloadFinishesBeforeSnapshotReplacement() throws Exception {
        var month = YearMonth.of(2026, 8);
        var report = new DesignerMonthlyReport(
                "designer-1", "设计师", "设计部", "设计师", month, java.time.LocalDateTime.now(), List.of());
        var reports = mock(DesignerMonthlyReportService.class);
        when(reports.buildMonth(month)).thenReturn(List.of(report));
        var exports = new DesignerPerformanceExportService(
                reports, mock(PerformanceImageLoader.class), tempDir.toString(), false, Runnable::run);
        exports.generate(month, "original");
        byte[] original = java.nio.file.Files.readAllBytes(
                exports.designerFile(month, "designer-1").orElseThrow());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var prepared = new java.util.concurrent.CountDownLatch(1);
        var captured = new java.io.ByteArrayOutputStream();
        var slowOutput = new java.io.OutputStream() {
            @Override
            public void write(int value) throws java.io.IOException {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new java.io.IOException("test timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(e);
                }
                captured.write(value);
            }
        };
        when(reports.buildMonth(month)).thenAnswer(call -> {
            prepared.countDown();
            return List.of(report);
        });
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var downloading = executor.submit(() -> {
                exports.writeZip(month, slowOutput);
                return null;
            });
            java.util.concurrent.Future<DesignerPerformanceExportService.Manifest> replacing = null;
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                replacing = executor.submit(() -> exports.generate(month, "replacement"));
                assertTrue(prepared.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var publication = replacing;
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.TimeoutException.class,
                        () -> publication.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            downloading.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(replacing);
            replacing.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(captured.toByteArray()))) {
            assertNotNull(zip.getNextEntry());
            org.junit.jupiter.api.Assertions.assertArrayEquals(original, zip.readAllBytes());
        }
        assertEquals("replacement", exports.manifest(month).orElseThrow().generatedBy());
    }
}
