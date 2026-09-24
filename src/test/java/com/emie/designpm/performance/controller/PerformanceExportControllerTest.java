package com.emie.designpm.performance.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.DesignerPerformanceExportService;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PerformanceExportControllerTest {
    @Test
    void zipEndpointStreamsZipInsteadOfPassingLambdaToMessageConverter() throws Exception {
        DesignerPerformanceExportService exports = mock(DesignerPerformanceExportService.class);
        when(exports.manifest(YearMonth.of(2026, 9)))
                .thenReturn(Optional.of(new DesignerPerformanceExportService.Manifest("2026-09", "", "", List.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PerformanceExportController(exports))
                .build();

        var started = mvc.perform(get("/api/performance/exports/2026-09/zip")
                        .requestAttr("authSession", new AuthSession("admin", "admin", "管理员")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.containsString("2026-09_%E8%AE%BE%E8%AE%A1")));
        verify(exports).writeZip(eq(YearMonth.of(2026, 9)), any());
    }
}
