package com.emie.designpm.performance.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.GrowthOsScoreService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GrowthOsScoreControllerTest {
    @Test
    void everyReadRequiresAdministrator() throws Exception {
        var service = mock(GrowthOsScoreService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GrowthOsScoreController(service))
                .build();
        for (String endpoint :
                List.of("/api/performance/growth-scores?month=2026-10", "/api/performance/growth-scores/2026-10/d1")) {
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get(endpoint).requestAttr("authSession", new AuthSession("d1", "designer", "测试设计师")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void exposesOnlyScoreViewToAdminAndRejectsInvalidParameters() throws Exception {
        var service = mock(GrowthOsScoreService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GrowthOsScoreController(service))
                .build();
        when(service.month("2026-10"))
                .thenReturn(
                        new GrowthOsScoreService.MonthScores("2026-10", false, "not_configured", "接口尚未配置", List.of()));
        mvc.perform(get("/api/performance/growth-scores")
                        .param("month", "2026-10")
                        .requestAttr("authSession", new AuthSession("admin", "admin", "测试管理员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_configured"))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.token").doesNotExist());
        when(service.forUser("2026-13", "d1")).thenThrow(new IllegalArgumentException("月份不合法"));
        mvc.perform(get("/api/performance/growth-scores/2026-13/d1")
                        .requestAttr("authSession", new AuthSession("admin", "admin", "测试管理员")))
                .andExpect(status().isBadRequest());
    }
}
