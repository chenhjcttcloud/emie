package com.emie.designpm.performance.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.DesignerMonthlyDeliveryService;
import com.emie.designpm.performance.service.PerformanceService;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesignerMonthlyDeliveryControllerTest {
    @Test
    void annualReportRemainsAdminOnlyAndRejectsInvalidPeriods() throws Exception {
        PerformanceService service = mock(PerformanceService.class);
        when(service.designerMonthlyReport("2026")).thenReturn(java.util.Map.of("month", "2026"));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new PerformanceController(service, mock(DesignerMonthlyDeliveryService.class)))
                .build();
        mvc.perform(get("/api/performance/designer-monthly-report")
                        .param("month", "2026")
                        .requestAttr("authSession", new AuthSession("designer", "d1", "设计师")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/performance/designer-monthly-report")
                        .param("month", "2026")
                        .requestAttr("authSession", new AuthSession("admin", "admin", "管理员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026"));
        mvc.perform(get("/api/performance/designer-monthly-report")
                        .param("month", "2026-13")
                        .requestAttr("authSession", new AuthSession("admin", "admin", "管理员")))
                .andExpect(status().isBadRequest());
        verify(service, times(1)).designerMonthlyReport("2026");
    }

    @Test
    void onlyAdminCanReadTaskDeliveries() throws Exception {
        DesignerMonthlyDeliveryService deliveries = mock(DesignerMonthlyDeliveryService.class);
        when(deliveries.forCompletedTask(YearMonth.of(2026, 9), 10L)).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new PerformanceController(mock(PerformanceService.class), deliveries))
                .build();
        mvc.perform(get("/api/performance/designer-monthly-report/task/10/deliveries")
                        .param("month", "2026-09")
                        .requestAttr("authSession", new AuthSession("designer", "designer-1", "设计师")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/performance/designer-monthly-report/task/10/deliveries")
                        .param("month", "2026-09")
                        .requestAttr("authSession", new AuthSession("admin", "admin", "管理员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions").isArray());
        verify(deliveries, times(1)).forCompletedTask(YearMonth.of(2026, 9), 10L);
    }

    @Test
    void monthlyExportRequiresAdminAndValidMonthAndRuleVersion() throws Exception {
        PerformanceService service = mock(PerformanceService.class);
        when(service.exportDesignerMonthlyReport(anyString(), nullable(String.class)))
                .thenCallRealMethod();
        var mvc = MockMvcBuilders.standaloneSetup(
                        new PerformanceController(service, mock(DesignerMonthlyDeliveryService.class)))
                .build();
        String endpoint = "/api/performance/designer-monthly-report/export";
        AuthSession admin = new AuthSession("admin", "admin", "管理员");
        mvc.perform(get(endpoint)
                        .param("month", "2026-11")
                        .requestAttr("authSession", new AuthSession("designer", "d1", "设计师")))
                .andExpect(status().isForbidden());
        mvc.perform(get(endpoint).param("month", "2026").requestAttr("authSession", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get(endpoint)
                        .param("month", "2026-11")
                        .param("ruleVersion", "unknown")
                        .requestAttr("authSession", admin))
                .andExpect(status().isBadRequest());
        verify(service, never()).exportDesignerMonthlyReport("2026-11", null);
        verify(service, never()).designerMonthlyReport(anyString());
    }

    @Test
    void monthlyExportKeepsAllRelatedItemsRegardlessOfPageRuleVersion() throws Exception {
        PerformanceService service = mock(PerformanceService.class);
        when(service.exportDesignerMonthlyReport(anyString(), nullable(String.class)))
                .thenCallRealMethod();
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 9, 15, 30);
        var tasks = List.of(
                Map.<String, Object>of(
                        "id",
                        11L,
                        "kind",
                        "task",
                        "ruleVersion",
                        "2.0",
                        "ruleCode",
                        "D20_09",
                        "completedAt",
                        completedAt,
                        "score",
                        2d,
                        "periodPoints",
                        2d,
                        "otherPeriodPoints",
                        0d),
                Map.<String, Object>ofEntries(
                        Map.entry("id", 12L),
                        Map.entry("kind", "task"),
                        Map.entry("name", "=SUM(1,2)"),
                        Map.entry("project", "项目甲"),
                        Map.entry("category", "包装设计"),
                        Map.entry("ruleVersion", "3.0"),
                        Map.entry("ruleCode", "VIS_V3_11"),
                        Map.entry("completedAt", completedAt),
                        Map.entry("score", 7.5d),
                        Map.entry("periodPoints", 7.5d),
                        Map.entry("otherPeriodPoints", 0d)),
                Map.<String, Object>of(
                        "id",
                        13L,
                        "kind",
                        "requirement",
                        "ruleVersion",
                        "3.0",
                        "ruleCode",
                        "VIS_V3_11",
                        "completedAt",
                        completedAt,
                        "score",
                        2.5d,
                        "periodPoints",
                        2.5d,
                        "otherPeriodPoints",
                        0d));
        var related = new ArrayList<Map<String, Object>>();
        for (var task : tasks) {
            var item = new LinkedHashMap<>(task);
            item.put("status", "completed");
            item.put("periodStatus", "completed_in_period");
            related.add(item);
        }
        related.add(Map.of(
                "id", 14L,
                "kind", "task",
                "name", "尚未完成的跨月事项",
                "status", "submitted_for_review",
                "periodStatus", "pending",
                "ruleVersion", "legacy",
                "ruleCode", "D30_9",
                "score", 2d,
                "periodPoints", 2d,
                "otherPeriodPoints", 0d));
        var points = List.of(
                Map.<String, Object>of("id", "L-1", "points", 2d),
                Map.<String, Object>of("id", "L-2", "points", 7.5d),
                Map.<String, Object>of("id", "A-3", "points", 2.5d),
                Map.<String, Object>of("id", "L-4", "points", 2d));
        when(service.designerMonthlyReport("2026-11"))
                .thenReturn(Map.of(
                        "month",
                        "2026-11",
                        "designers",
                        List.of(Map.of(
                                "userId",
                                "d1",
                                "designer",
                                "设计师甲",
                                "score",
                                14d,
                                "completedCount",
                                3,
                                "tasks",
                                tasks,
                                "relatedItems",
                                related,
                                "relatedCount",
                                4,
                                "pointItems",
                                points))));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new PerformanceController(service, mock(DesignerMonthlyDeliveryService.class)))
                .build();
        String endpoint = "/api/performance/designer-monthly-report/export";
        AuthSession admin = new AuthSession("admin", "admin", "管理员");
        byte[] bytes = mvc.perform(get(endpoint)
                        .param("month", "2026-11")
                        .param("ruleVersion", "3.0")
                        .requestAttr("authSession", admin))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"designer-monthly-report-2026-11.xlsx\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals(14d, workbook.getSheet("月度积分汇总").getRow(1).getCell(3).getNumericCellValue());
            assertEquals(5, workbook.getSheet("当月积分明细").getPhysicalNumberOfRows());
            var sheet = workbook.getSheet("当月关联事项");
            assertEquals(5, sheet.getPhysicalNumberOfRows());
            assertEquals("事项状态", sheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals("事项累计积分（含其他月份）", sheet.getRow(0).getCell(11).getStringCellValue());
            assertEquals("当月归属积分（核对用）", sheet.getRow(0).getCell(13).getStringCellValue());
            assertEquals("2.0", sheet.getRow(1).getCell(9).getStringCellValue());
            var row = sheet.getRow(2);
            assertEquals("2026-11", row.getCell(0).getStringCellValue());
            assertEquals("设计师甲", row.getCell(1).getStringCellValue());
            assertEquals("项目甲", row.getCell(2).getStringCellValue());
            assertEquals("12", row.getCell(4).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(5).getCellType());
            assertEquals("=SUM(1,2)", row.getCell(5).getStringCellValue());
            assertEquals(completedAt, row.getCell(7).getLocalDateTimeCellValue());
            assertEquals("包装设计", row.getCell(8).getStringCellValue());
            assertEquals("3.0", row.getCell(9).getStringCellValue());
            assertEquals("VIS_V3_11", row.getCell(10).getStringCellValue());
            assertEquals(7.5d, row.getCell(11).getNumericCellValue());
            assertEquals(7.5d, row.getCell(13).getNumericCellValue());
            assertEquals("设计/送审需求", sheet.getRow(3).getCell(3).getStringCellValue());
            assertEquals("尚未完成的跨月事项", sheet.getRow(4).getCell(5).getStringCellValue());
            double relatedPoints = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++)
                relatedPoints += sheet.getRow(i).getCell(13).getNumericCellValue();
            assertEquals(14d, relatedPoints);
        }
        byte[] unfiltered = mvc.perform(get(endpoint).param("month", "2026-11").requestAttr("authSession", admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(unfiltered))) {
            assertEquals(5, workbook.getSheet("当月关联事项").getPhysicalNumberOfRows());
            assertEquals("2.0", workbook.getSheet("当月关联事项").getRow(1).getCell(9).getStringCellValue());
            assertEquals(14d, workbook.getSheet("月度积分汇总").getRow(1).getCell(3).getNumericCellValue());
        }
    }
}
