package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DesignerPerformanceWorkbookTest {
    @Test
    void appraisalUsesRevisedSeventyTenTenTenWeights() throws Exception {
        var month = YearMonth.of(2026, 9);
        var task = new DesignerMonthlyReport.Item(
                DesignerMonthlyReport.Category.REGULAR,
                "设计任务",
                LocalDateTime.of(2026, 9, 1, 9, 0),
                LocalDate.of(2026, 9, 30),
                LocalDateTime.of(2026, 9, 20, 17, 0),
                List.of(),
                List.of());
        var report = new DesignerMonthlyReport(
                "designer-1", "设计师", "设计部", "设计师", month, LocalDateTime.of(2026, 9, 24, 12, 0), List.of(task));
        var bytes = new ByteArrayOutputStream();
        DesignerPerformanceWorkbook.write(report, url -> Optional.empty(), bytes);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
            var sheet = workbook.getSheet("绩效考评表");
            assertEquals(0.7, sheet.getRow(5).getCell(7).getNumericCellValue());
            assertEquals(100, sheet.getRow(5).getCell(8).getNumericCellValue());
            for (int row = 6; row <= 8; row++) {
                assertEquals(0.1, sheet.getRow(row).getCell(7).getNumericCellValue());
            }
            assertEquals("3", sheet.getRow(6).getCell(0).getStringCellValue());
            assertEquals("4", sheet.getRow(7).getCell(0).getStringCellValue());
            assertEquals("SUM(J6:J9)", sheet.getRow(9).getCell(9).getCellFormula());
            assertEquals("统计归属", workbook.getSheet("任务详情").getRow(3).getCell(7).getStringCellValue());
        }
    }
}
