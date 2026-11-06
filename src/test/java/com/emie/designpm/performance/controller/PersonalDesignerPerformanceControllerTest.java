package com.emie.designpm.performance.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.MonthlyDesignerEvaluationService;
import com.emie.designpm.performance.service.PerformanceService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PersonalDesignerPerformanceControllerTest {
    private final PerformanceService performance = mock(PerformanceService.class);
    private final MonthlyDesignerEvaluationService evaluations = mock(MonthlyDesignerEvaluationService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new PersonalDesignerPerformanceController(performance, evaluations))
            .build();
    private final AuthSession designer = new AuthSession("d1", "designer", "设计师");

    @Test
    void onlySignedInDesignersCanReadPersonalPerformance() throws Exception {
        mvc.perform(get("/api/performance/my-report").param("period", "2026-10"))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("admin", "planner", "sales")) {
            mvc.perform(get("/api/performance/my-report")
                            .param("period", "2026-10")
                            .requestAttr("authSession", new AuthSession("other", role, "其他角色")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(performance, evaluations);
    }

    @Test
    void monthUsesSessionIdentityEvenWhenAnotherUserIsRequested() throws Exception {
        when(performance.designerMonthlyReport("2026-10", "d1"))
                .thenReturn(Map.of("month", "2026-10", "designers", List.of(Map.of("userId", "d1", "score", 75))));
        when(evaluations.completion("2026-10", "d1"))
                .thenReturn(new MonthlyDesignerEvaluationService.Completion(
                        BigDecimal.valueOf(75), BigDecimal.valueOf(75), 100, "月度所得积分 75 ÷ 目标积分 100"));
        mvc.perform(get("/api/performance/my-report")
                        .param("period", "2026-10")
                        .param("userId", "d2")
                        .requestAttr("authSession", designer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("d1"))
                .andExpect(jsonPath("$.designers.length()").value(1))
                .andExpect(jsonPath("$.designers[0].userId").value("d1"))
                .andExpect(jsonPath("$.designers[0].completionPreview.earnedPoints")
                        .value(75))
                .andExpect(jsonPath("$.designers[0].completionPreview.targetPoints")
                        .value(100))
                .andExpect(jsonPath("$.designers[0].evaluations").isArray());
        verify(performance).designerMonthlyReport("2026-10", "d1");
        verify(evaluations).listForUser("2026-10", "d1");
        verify(evaluations).completion("2026-10", "d1");
        verifyNoMoreInteractions(performance, evaluations);
    }

    @Test
    void annualReportDoesNotCalculateAMonthlyPreview() throws Exception {
        when(performance.designerMonthlyReport("2026", "d1"))
                .thenReturn(Map.of("month", "2026", "designers", List.of(Map.of("userId", "d1"))));
        mvc.perform(get("/api/performance/my-report").param("period", "2026").requestAttr("authSession", designer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026"))
                .andExpect(jsonPath("$.designers[0].completionPreview").doesNotExist());
        verify(evaluations).listForUser("2026", "d1");
        verify(evaluations, never()).completion(anyString(), anyString());
    }

    @Test
    void invalidPeriodsAreRejectedBeforeLoadingData() throws Exception {
        for (String period : List.of("", "2026-13", "2026-1", "2026-10-01", "0000", "2026-00")) {
            mvc.perform(get("/api/performance/my-report")
                            .param("period", period)
                            .requestAttr("authSession", designer))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(performance, evaluations);
    }
}
