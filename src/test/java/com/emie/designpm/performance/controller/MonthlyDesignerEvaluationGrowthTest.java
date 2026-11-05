package com.emie.designpm.performance.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.performance.service.GrowthOsScoreService;
import com.emie.designpm.performance.service.MonthlyDesignerEvaluationService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

class MonthlyDesignerEvaluationGrowthTest {
    private final MonthlyDesignerEvaluationService evaluations = mock(MonthlyDesignerEvaluationService.class);
    private final GrowthOsScoreService growth = mock(GrowthOsScoreService.class);
    private final MonthlyDesignerEvaluationController controller =
            new MonthlyDesignerEvaluationController(evaluations, growth);

    private MockHttpServletRequest administrator() {
        var request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("a1", "admin", "管理员"));
        return request;
    }

    private MonthlyDesignerEvaluationService.Scores submitted() {
        return new MonthlyDesignerEvaluationService.Scores(
                7L,
                new BigDecimal("110"),
                new BigDecimal("100"),
                new BigDecimal("83"),
                new BigDecimal("100"),
                "本月评价",
                new BigDecimal("5"),
                "完成原因",
                "客户端协作原因",
                "考勤原因",
                "客户端周报原因",
                "迟交扣分原因");
    }

    private GrowthOsScoreService.Score published(String number) {
        return new GrowthOsScoreService.Score(
                new BigDecimal(number),
                "published",
                true,
                null,
                "automatic_report",
                "2026-11-05T01:00:00Z",
                "a".repeat(64));
    }

    private GrowthOsScoreService.Score pending(String previous) {
        return new GrowthOsScoreService.Score(
                null,
                "rescoring",
                false,
                previous == null ? null : new BigDecimal(previous),
                "automatic_report",
                null,
                null);
    }

    private void current(String match, GrowthOsScoreService.Score weekly, GrowthOsScoreService.Score collaboration) {
        when(growth.isConfigured()).thenReturn(true);
        when(growth.forUser("2026-10", "d1"))
                .thenReturn(
                        new GrowthOsScoreService.UserScores("d1", "feishu-d1", "测试设计师", match, weekly, collaboration));
    }

    private MonthlyDesignerEvaluationService.View saved(String collaboration, String weekly) {
        return new MonthlyDesignerEvaluationService.View(
                "d1",
                "2026-10",
                7L,
                new BigDecimal("100"),
                collaboration == null ? null : new BigDecimal(collaboration),
                new BigDecimal("90"),
                weekly == null ? null : new BigDecimal(weekly),
                "旧评语",
                BigDecimal.ZERO,
                new BigDecimal("90"),
                "old-admin",
                null,
                "旧完成",
                "旧协作来源",
                "旧考勤",
                "旧周报来源",
                null);
    }

    private MonthlyDesignerEvaluationService.Scores captured() {
        var captor = ArgumentCaptor.forClass(MonthlyDesignerEvaluationService.Scores.class);
        verify(evaluations).save(eq("2026-10"), eq("d1"), captor.capture(), eq("a1"));
        return captor.getValue();
    }

    @Test
    void disabledIntegrationPreservesManualScoresAndReasons() {
        var input = submitted();
        assertEquals(
                200,
                controller
                        .save("2026-10", "d1", input, administrator())
                        .getStatusCode()
                        .value());
        assertSame(input, captured());
        verify(growth, never()).forUser(anyString(), anyString());
        verify(evaluations, never()).list(anyString());
    }

    @Test
    void publishedScoresReplaceClientForgeryAndPreserveOtherFormFields() {
        current("matched", published("72.5"), published("81.25"));
        when(evaluations.list("2026-10")).thenReturn(List.of());
        var input = submitted();
        assertEquals(
                200,
                controller
                        .save("2026-10", "d1", input, administrator())
                        .getStatusCode()
                        .value());
        var trusted = captured();
        assertEquals(new BigDecimal("72.5"), trusted.weeklyReportScore());
        assertEquals(new BigDecimal("81.25"), trusted.collaborationScore());
        assertEquals(input.version(), trusted.version());
        assertEquals(input.completionScore(), trusted.completionScore());
        assertEquals(input.attendanceScore(), trusted.attendanceScore());
        assertEquals(input.weeklyReportDeduction(), trusted.weeklyReportDeduction());
        assertEquals(input.comment(), trusted.comment());
        assertEquals(input.completionReason(), trusted.completionReason());
        assertEquals(input.attendanceReason(), trusted.attendanceReason());
        assertEquals(input.weeklyReportDeductionReason(), trusted.weeklyReportDeductionReason());
        assertTrue(trusted.weeklyReportReason().contains("飞书 user_id=feishu-d1"));
        assertTrue(trusted.weeklyReportReason().contains("版本=" + "a".repeat(64)));
        assertTrue(trusted.collaborationReason().contains("360协同"));
        assertFalse(trusted.collaborationReason().contains("客户端"));
    }

    @Test
    void zeroIsAValidPublishedScoreForBothDimensions() {
        current("matched", published("0"), published("0"));
        when(evaluations.list("2026-10")).thenReturn(List.of());
        assertEquals(
                200,
                controller
                        .save("2026-10", "d1", submitted(), administrator())
                        .getStatusCode()
                        .value());
        assertEquals(BigDecimal.ZERO, captured().weeklyReportScore());
        assertEquals(BigDecimal.ZERO, captured().collaborationScore());
    }

    @Test
    void pendingScoresCannotCreateEvaluationUsingClientOrPreviousValues() {
        current("matched", pending("95"), published("84"));
        when(evaluations.list("2026-10")).thenReturn(List.of());
        var response = controller.save("2026-10", "d1", submitted(), administrator());
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("尚未发布"));
        verify(evaluations, never()).save(anyString(), anyString(), any(), anyString());
    }

    @Test
    void savedWeeklyScoreAndItsProvenanceSurviveRescoringIndependently() {
        current("matched", pending("99"), published("84"));
        when(evaluations.list("2026-10")).thenReturn(List.of(saved("65", "73")));
        assertEquals(
                200,
                controller
                        .save("2026-10", "d1", submitted(), administrator())
                        .getStatusCode()
                        .value());
        var trusted = captured();
        assertEquals(new BigDecimal("73"), trusted.weeklyReportScore());
        assertEquals("旧周报来源", trusted.weeklyReportReason());
        assertEquals(new BigDecimal("84"), trusted.collaborationScore());
        assertTrue(trusted.collaborationReason().contains("成长周报 OS"));
    }

    @Test
    void savedCollaborationScoreAndItsProvenanceSurviveRescoringIndependently() {
        current("matched", published("79"), pending(null));
        when(evaluations.list("2026-10")).thenReturn(List.of(saved("65", "73")));
        assertEquals(
                200,
                controller
                        .save("2026-10", "d1", submitted(), administrator())
                        .getStatusCode()
                        .value());
        var trusted = captured();
        assertEquals(new BigDecimal("65"), trusted.collaborationScore());
        assertEquals("旧协作来源", trusted.collaborationReason());
        assertEquals(new BigDecimal("79"), trusted.weeklyReportScore());
        assertTrue(trusted.weeklyReportReason().contains("成长周报 OS"));
    }

    @Test
    void nameMismatchNeverAcceptsPublishedScoresForAnotherIdentity() {
        current("name_mismatch", published("98"), published("99"));
        when(evaluations.list("2026-10")).thenReturn(List.of());
        assertEquals(
                400,
                controller
                        .save("2026-10", "d1", submitted(), administrator())
                        .getStatusCode()
                        .value());
        verify(evaluations, never()).save(anyString(), anyString(), any(), anyString());
        when(evaluations.list("2026-10")).thenReturn(List.of(saved("65", "73")));
        assertEquals(
                200,
                controller
                        .save("2026-10", "d1", submitted(), administrator())
                        .getStatusCode()
                        .value());
        var trusted = captured();
        assertEquals(new BigDecimal("65"), trusted.collaborationScore());
        assertEquals(new BigDecimal("73"), trusted.weeklyReportScore());
        assertEquals("旧协作来源", trusted.collaborationReason());
        assertEquals("旧周报来源", trusted.weeklyReportReason());
    }

    @Test
    void wrongDesignerSavedRowsCannotSupplyFallbackScores() {
        current("matched", pending("95"), pending(null));
        var other = saved("65", "73");
        when(evaluations.list("2026-10"))
                .thenReturn(List.of(new MonthlyDesignerEvaluationService.View(
                        "another-designer",
                        other.monthKey(),
                        other.version(),
                        other.completionScore(),
                        other.collaborationScore(),
                        other.attendanceScore(),
                        other.weeklyReportScore(),
                        other.comment(),
                        other.weeklyReportDeduction(),
                        other.totalScore(),
                        other.updatedBy(),
                        other.updatedAt(),
                        other.completionReason(),
                        other.collaborationReason(),
                        other.attendanceReason(),
                        other.weeklyReportReason(),
                        other.weeklyReportDeductionReason())));
        assertEquals(
                400,
                controller
                        .save("2026-10", "d1", submitted(), administrator())
                        .getStatusCode()
                        .value());
        verify(evaluations, never()).save(anyString(), anyString(), any(), anyString());
    }
}
