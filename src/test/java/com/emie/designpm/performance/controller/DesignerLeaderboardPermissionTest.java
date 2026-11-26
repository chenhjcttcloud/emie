package com.emie.designpm.performance.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.auth.AuthSessions;
import com.emie.designpm.config.AuthFilter;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.entity.User;
import com.emie.designpm.performance.service.DesignerMonthlyDeliveryService;
import com.emie.designpm.performance.service.PerformanceService;
import com.emie.designpm.points.controller.PointGovernanceController;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.service.PointManualAdjustmentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP permissions run through the real authentication filter and real manual-adjustment service. */
class DesignerLeaderboardPermissionTest {
    private static final String OVERVIEW = "/api/performance/designer-leaderboard";
    private static final String ADJUST = "/api/point-governance/manual-adjustment";
    private PerformanceService performance;
    private PointAdjustmentLedgerRepository adjustments;
    private UserRepository users;
    private MockMvc mvc;
    private final List<String> tokens = new ArrayList<>();

    @BeforeEach
    void setup() {
        performance = mock(PerformanceService.class);
        adjustments = mock(PointAdjustmentLedgerRepository.class);
        users = mock(UserRepository.class);
        var governance =
                new PointGovernanceController(null, null, null, new PointManualAdjustmentService(adjustments, users));
        mvc = MockMvcBuilders.standaloneSetup(
                        new PerformanceController(performance, mock(DesignerMonthlyDeliveryService.class)), governance)
                .addFilters(new AuthFilter())
                .build();
    }

    @AfterEach
    void removeTestSessions() {
        tokens.forEach(AuthSessions::remove);
    }

    @ParameterizedTest
    @ValueSource(strings = {"planner", "admin", "产品企划", "企划", "管理员"})
    void plannerAndAdminCanReadMonthlyAndCumulativeDesignerScores(String role) throws Exception {
        when(performance.designerLeaderboard("2026-10"))
                .thenReturn(List.of(
                        Map.of("userId", "designer-1", "name", "设计师一", "points", 25d),
                        Map.of("userId", "designer-zero", "name", "零分设计师", "points", 0d)));
        when(performance.designerLeaderboard(null))
                .thenReturn(List.of(
                        Map.of("userId", "designer-1", "name", "设计师一", "points", 40.5d),
                        Map.of("userId", "designer-zero", "name", "零分设计师", "points", 0d)));
        String token = token(new AuthSession("reader-1", role, "只读积分查看者"));

        mvc.perform(get(OVERVIEW).param("month", "2026-10").header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].points").value(25))
                .andExpect(jsonPath("$[1].points").value(0));
        mvc.perform(get(OVERVIEW).header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
                .andExpect(jsonPath("$[0].points").value(40.5));
        verify(performance).designerLeaderboard("2026-10");
        verify(performance).designerLeaderboard(null);
        verifyNoInteractions(adjustments, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026", "2026-1"})
    void invalidMonthsReturnBadRequestInsteadOfAnInternalServerError(String month) throws Exception {
        when(performance.designerLeaderboard(month)).thenThrow(new IllegalArgumentException("月份格式无效"));
        String token = token(new AuthSession("planner-1", "planner", "产品企划"));
        mvc.perform(get(OVERVIEW).param("month", month).header("X-Auth-Token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("月份格式应为 YYYY-MM"));
        verify(performance).designerLeaderboard(month);
        verifyNoInteractions(adjustments, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"designer", "sales", "promotion", "supplychain", "pending", "custom"})
    void unrelatedRolesCannotReadTheNewGlobalDesignerOverview(String role) throws Exception {
        String token = token(new AuthSession("restricted-1", role, "其他角色"));
        mvc.perform(get(OVERVIEW).param("month", "2026-10").header("X-Auth-Token", token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(performance, adjustments, users);
    }

    @Test
    void unauthenticatedReadsAndWritesAreRejectedBeforeControllers() throws Exception {
        mvc.perform(get(OVERVIEW).param("month", "2026-10")).andExpect(status().isUnauthorized());
        mvc.perform(get(OVERVIEW).header("X-Auth-Token", "unregistered-test-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ADJUST).contentType(MediaType.APPLICATION_JSON).content(adjustmentBody(2.5)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(performance, adjustments, users);
    }

    @ParameterizedTest
    @CsvSource({"planner,2.5", "planner,-0.25", "产品企划,2.5", "企划,-0.25"})
    void plannersCannotManuallyAddOrDeductEvenWhenTheRequestClaimsToBeAdmin(String role, double points)
            throws Exception {
        String token = token(new AuthSession("planner-1", role, "产品企划"));
        mvc.perform(post(ADJUST)
                        .header("X-Auth-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(points)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adjustments, users, performance);
    }

    @Test
    void anAdministratorSwitchedToPlannerRemainsReadOnlyUntilSwitchingBack() throws Exception {
        String token = token(new AuthSession("planner-1", "planner", "切换后的企划", "original-admin", "admin"));
        mvc.perform(post(ADJUST)
                        .header("X-Auth-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(-0.25)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adjustments, users, performance);
    }

    @ParameterizedTest
    @ValueSource(doubles = {2.5, -0.25})
    void administratorsRetainManualAdjustmentAndServerSideAuditFields(double points) throws Exception {
        var designer = User.builder()
                .userId("designer-1")
                .name("设计师一")
                .role("designer")
                .build();
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(designer));
        when(adjustments.maxSourceIdByType("MANUAL")).thenReturn(41L);
        when(adjustments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String token = token(new AuthSession("admin-1", "admin", "管理员"));

        mvc.perform(post(ADJUST)
                        .header("X-Auth-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody(points)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(points))
                .andExpect(jsonPath("$.userId").value("designer-1"))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.createdBy").value("admin-1"))
                .andExpect(jsonPath("$.reason").value("积分更正"));
        var captured = ArgumentCaptor.forClass(PointAdjustmentLedger.class);
        verify(adjustments).saveAndFlush(captured.capture());
        assertEquals(points, captured.getValue().getPoints());
        assertEquals("admin-1", captured.getValue().getCreatedBy());
        assertEquals("积分更正", captured.getValue().getReason());
        assertEquals(42L, captured.getValue().getSourceId());
        verifyNoInteractions(performance);
    }

    @Test
    void readingDesignerScoresDoesNotGrantPlannerTheExistingPerformanceTargetWrite() throws Exception {
        String token = token(new AuthSession("planner-1", "planner", "产品企划"));
        mvc.perform(put("/api/performance/designer-targets/designer-1")
                        .header("X-Auth-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthKey\":\"2026-10\",\"targetPoints\":100,\"role\":\"admin\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(performance, adjustments, users);
    }

    @Test
    void newOverviewDoesNotExposeExistingAdministrativePerformanceReportsOrConfiguration() throws Exception {
        String token = token(new AuthSession("planner-1", "planner", "产品企划"));
        for (String endpoint : List.of(
                "/api/performance/designer-monthly-report",
                "/api/performance/designer-monthly-report/export",
                "/api/performance/standard")) {
            mvc.perform(get(endpoint).param("month", "2026-10").header("X-Auth-Token", token))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(performance, adjustments, users);
    }

    private String token(AuthSession session) {
        String token = AuthSessions.generateToken();
        AuthSessions.put(token, session);
        tokens.add(token);
        return token;
    }

    private static String adjustmentBody(double points) {
        return "{\"userId\":\"designer-1\",\"points\":" + points
                + ",\"reason\":\"  积分更正  \",\"role\":\"admin\",\"createdBy\":\"forged-admin\"}";
    }
}
