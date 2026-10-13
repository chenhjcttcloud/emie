package com.emie.designpm.performance.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.MonthlyDesignerEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonthlyDesignerEvaluationControllerTest {
    @Test
    void readsRequireAdminAndConflictsReturn409() throws Exception {
        var service = mock(MonthlyDesignerEvaluationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MonthlyDesignerEvaluationController(service))
                .build();
        mvc.perform(get("/api/performance/designer-evaluations").param("period", "2026"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/performance/designer-evaluations")
                        .param("period", "2026")
                        .requestAttr("authSession", new AuthSession("d1", "designer", "设计师")))
                .andExpect(status().isForbidden());
        when(service.save(anyString(), anyString(), any(), anyString()))
                .thenThrow(new OptimisticLockingFailureException("stale"));
        mvc.perform(put("/api/performance/designer-evaluations/2026-10/d1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("authSession", new AuthSession("a1", "admin", "管理员")))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsNonFiniteNumbersBeforeSaving() throws Exception {
        var service = mock(MonthlyDesignerEvaluationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MonthlyDesignerEvaluationController(service))
                .build();
        for (String value : new String[] {"NaN", "Infinity", "-Infinity"}) {
            mvc.perform(put("/api/performance/designer-evaluations/2026-10/d1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"weeklyReportDeduction\":\"" + value + "\"}")
                            .requestAttr("authSession", new AuthSession("a1", "admin", "管理员")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void completionIsAdminOnlyAndViewsHideRetiredScores() throws Exception {
        var service = mock(MonthlyDesignerEvaluationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MonthlyDesignerEvaluationController(service))
                .build();
        mvc.perform(get("/api/performance/designer-evaluations/completion")
                        .param("month", "2026-10")
                        .param("userId", "d1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/performance/designer-evaluations/completion")
                        .param("month", "2026-10")
                        .param("userId", "d1")
                        .requestAttr("authSession", new AuthSession("d1", "designer", "设计师")))
                .andExpect(status().isForbidden());
        when(service.completion("2026-10", "d1"))
                .thenReturn(new MonthlyDesignerEvaluationService.Completion(null, java.math.BigDecimal.ZERO, 0, null));
        mvc.perform(get("/api/performance/designer-evaluations/completion")
                        .param("month", "2026-10")
                        .param("userId", "d1")
                        .requestAttr("authSession", new AuthSession("a1", "admin", "管理员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetPoints").value(0));
        var n = java.math.BigDecimal.valueOf(100);
        when(service.list("2026"))
                .thenReturn(java.util.List.of(new MonthlyDesignerEvaluationService.View(
                        "d1",
                        "2026-10",
                        0L,
                        n,
                        n,
                        n,
                        n,
                        null,
                        java.math.BigDecimal.ZERO,
                        n,
                        "a1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
        mvc.perform(get("/api/performance/designer-evaluations")
                        .param("period", "2026")
                        .requestAttr("authSession", new AuthSession("a1", "admin", "管理员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adjustmentScore").doesNotExist())
                .andExpect(jsonPath("$[0].aestheticScore").doesNotExist())
                .andExpect(jsonPath("$[0].initiativeScore").doesNotExist());
    }
}
