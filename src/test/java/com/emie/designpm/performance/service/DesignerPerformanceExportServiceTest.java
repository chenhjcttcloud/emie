package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
        assertEquals(1, queued.size());
        queued.getFirst().run();

        assertEquals("READY", exports.generationStatus(YearMonth.of(2026, 8)).status());
        assertTrue(exports.manifest(YearMonth.of(2026, 8)).isPresent());
    }
}
