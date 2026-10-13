package com.emie.designpm.performance.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.DesignerMonthlyDeliveryService;
import com.emie.designpm.performance.service.PerformanceService;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
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
}
