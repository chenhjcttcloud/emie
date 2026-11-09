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
    void monthlyExportUsesTaskRowsAndSelectedVersionFromReport() throws Exception {
        PerformanceService service = mock(PerformanceService.class);
        when(service.exportDesignerMonthlyReport(anyString(), nullable(String.class)))
                .thenCallRealMethod();
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 9, 15, 30);
        var tasks = List.of(
                Map.of(
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
                        2d),
                Map.of(
                        "id", 12L,
                        "kind", "task",
                        "name", "=SUM(1,2)",
                        "project", "项目甲",
                        "category", "包装设计",
                        "ruleVersion", "3.0",
                        "ruleCode", "VIS_V3_11",
                        "completedAt", completedAt,
                        "score", 7.5d),
                Map.of("id", 13L, "kind", "requirement", "ruleCode", "VIS_V3_11", "completedAt", completedAt));
        when(service.designerMonthlyReport("2026-11"))
                .thenReturn(
                        Map.of("month", "2026-11", "designers", List.of(Map.of("designer", "设计师甲", "tasks", tasks))));
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
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getPhysicalNumberOfRows());
            assertEquals("规则版本", sheet.getRow(0).getCell(7).getStringCellValue());
            assertEquals("任务累计积分", sheet.getRow(0).getCell(9).getStringCellValue());
            var row = sheet.getRow(1);
            assertEquals("2026-11", row.getCell(0).getStringCellValue());
            assertEquals("设计师甲", row.getCell(1).getStringCellValue());
            assertEquals("项目甲", row.getCell(2).getStringCellValue());
            assertEquals("12", row.getCell(3).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(4).getCellType());
            assertEquals("=SUM(1,2)", row.getCell(4).getStringCellValue());
            assertEquals(completedAt, row.getCell(5).getLocalDateTimeCellValue());
            assertEquals("包装设计", row.getCell(6).getStringCellValue());
            assertEquals("3.0", row.getCell(7).getStringCellValue());
            assertEquals("VIS_V3_11", row.getCell(8).getStringCellValue());
            assertEquals(7.5d, row.getCell(9).getNumericCellValue());
        }
        byte[] unfiltered = mvc.perform(get(endpoint).param("month", "2026-11").requestAttr("authSession", admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(unfiltered))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(3, sheet.getPhysicalNumberOfRows());
            assertEquals("2.0", sheet.getRow(1).getCell(7).getStringCellValue());
        }
    }
}
