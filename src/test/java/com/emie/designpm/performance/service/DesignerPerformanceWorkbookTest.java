package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DesignerPerformanceWorkbookTest {
    @Test
    void embedsDeliveryImagesAndAddsDownloadLinkForOtherAttachments() throws Exception {
        var imageBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
        var loadedImage =
                PerformanceImageLoader.encode(ImageIO.read(new ByteArrayInputStream(imageBytes.toByteArray())));
        var delivery = new DesignerMonthlyReport.Delivery(
                1,
                "initial",
                "首次交付",
                LocalDateTime.of(2026, 9, 10, 12, 0),
                "设计师",
                "截图成果",
                List.of(
                        new DesignerMonthlyReport.Attachment("效果图.png", "/api/files/download/image.png", true),
                        new DesignerMonthlyReport.Attachment("源文件.ai", "/api/files/download/source.ai", false)));
        var item = new DesignerMonthlyReport.Item(
                DesignerMonthlyReport.Category.REGULAR,
                "设计任务",
                LocalDateTime.of(2026, 9, 1, 9, 0),
                LocalDateTime.of(2026, 9, 1, 9, 0),
                true,
                LocalDate.of(2026, 9, 30),
                null,
                "截图成果",
                delivery.attachments(),
                List.of(delivery));
        var report = new DesignerMonthlyReport(
                "designer-1", "设计师", "设计部", "设计师", YearMonth.of(2026, 9), LocalDateTime.now(), List.of(item));
        var bytes = new ByteArrayOutputStream();
        DesignerPerformanceWorkbook.write(
                report, url -> url.endsWith("image.png") ? Optional.of(loadedImage) : Optional.empty(), bytes);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
            var sheet = workbook.getSheet("交付成果明细");
            assertEquals(2, workbook.getAllPictures().size());
            assertEquals(1, sheet.getDrawingPatriarch().getShapes().size());
            assertEquals("源文件.ai", sheet.getRow(3).getCell(9).getStringCellValue());
            assertNotNull(sheet.getRow(3).getCell(9).getHyperlink());
            assertEquals(
                    "/api/files/download/source.ai",
                    sheet.getRow(3).getCell(9).getHyperlink().getAddress());
        }
    }

    @Test
    void countsTasksByDesignerReceiptMonthAndKeepsCarryOverCompletionsSeparate() {
        var month = YearMonth.of(2026, 9);
        var claimedThisMonth = new DesignerMonthlyReport.Item(
                DesignerMonthlyReport.Category.REGULAR,
                "市场接单",
                LocalDateTime.of(2026, 8, 28, 9, 0),
                LocalDateTime.of(2026, 9, 2, 9, 0),
                false,
                LocalDate.of(2026, 9, 30),
                null,
                "",
                List.of(),
                List.of());
        var completedCarryOver = new DesignerMonthlyReport.Item(
                DesignerMonthlyReport.Category.REGULAR,
                "往月任务",
                LocalDateTime.of(2026, 8, 1, 9, 0),
                LocalDateTime.of(2026, 8, 1, 9, 0),
                true,
                LocalDate.of(2026, 9, 10),
                LocalDateTime.of(2026, 9, 8, 17, 0),
                "",
                List.of(),
                List.of());
        var report = new DesignerMonthlyReport(
                "designer-1",
                "设计师",
                "设计部",
                "设计师",
                month,
                LocalDateTime.now(),
                List.of(claimedThisMonth, completedCarryOver));

        assertEquals(1, report.createdCount());
        assertEquals(0, report.completedCount());
        assertEquals(1, report.carriedOverCompleted().size());
    }

    @Test
    void appraisalUsesRevisedSeventyTenTenTenWeights() throws Exception {
        var month = YearMonth.of(2026, 9);
        var task = new DesignerMonthlyReport.Item(
                DesignerMonthlyReport.Category.REGULAR,
                "设计任务",
                LocalDateTime.of(2026, 9, 1, 9, 0),
                LocalDateTime.of(2026, 9, 1, 9, 0),
                true,
                LocalDate.of(2026, 9, 30),
                LocalDateTime.of(2026, 9, 20, 17, 0),
                "包装主视觉、内页插画和生产文件",
                List.of(),
                List.of(
                        new DesignerMonthlyReport.Delivery(
                                1, "initial", "首次交付", LocalDateTime.of(2026, 9, 10, 12, 0), "设计师", "首版包装视觉", List.of()),
                        new DesignerMonthlyReport.Delivery(
                                2,
                                "revision",
                                "修改交付",
                                LocalDateTime.of(2026, 9, 18, 12, 0),
                                "设计师",
                                "修订版包装及生产文件",
                                List.of())));
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
            var details = workbook.getSheet("任务详情");
            assertEquals("本月完成进度", details.getRow(2).getCell(0).getStringCellValue());
            assertEquals(1d, details.getRow(2).getCell(1).getNumericCellValue());
            assertEquals(
                    org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex(),
                    details.getRow(2).getCell(1).getCellStyle().getFillForegroundColor());
            assertEquals(1, details.getSheetConditionalFormatting().getNumConditionalFormattings());
            assertNotNull(details.getSheetConditionalFormatting()
                    .getConditionalFormattingAt(0)
                    .getRule(0)
                    .getDataBarFormatting());
            assertEquals("统计归属", details.getRow(3).getCell(7).getStringCellValue());
            assertEquals("交付成果描述", details.getRow(3).getCell(8).getStringCellValue());
            assertEquals("包装主视觉、内页插画和生产文件", details.getRow(7).getCell(8).getStringCellValue());
            assertEquals("2026-09-01（估算）", details.getRow(7).getCell(3).getStringCellValue());
            var deliveryDetails = workbook.getSheet("交付成果明细");
            assertEquals("首版包装视觉", deliveryDetails.getRow(3).getCell(8).getStringCellValue());
            assertEquals("修订版包装及生产文件", deliveryDetails.getRow(4).getCell(8).getStringCellValue());
        }
    }
}
