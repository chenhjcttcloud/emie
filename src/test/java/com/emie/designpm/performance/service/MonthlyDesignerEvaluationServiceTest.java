package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.MonthlyDesignerEvaluation;
import com.emie.designpm.entity.MonthlyUserPointTarget;
import com.emie.designpm.entity.User;
import com.emie.designpm.performance.repository.MonthlyDesignerEvaluationRepository;
import com.emie.designpm.performance.repository.MonthlyUserPointTargetRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MonthlyDesignerEvaluationServiceTest {
    private final MonthlyDesignerEvaluationRepository repository = mock(MonthlyDesignerEvaluationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PerformanceService performance = mock(PerformanceService.class);
    private final MonthlyUserPointTargetRepository targets = mock(MonthlyUserPointTargetRepository.class);
    private final MonthlyDesignerEvaluationService service =
            new MonthlyDesignerEvaluationService(repository, users, performance, targets);

    private MonthlyDesignerEvaluationService.Scores scores(Long version, int completion) {
        var n = BigDecimal.valueOf(100);
        return new MonthlyDesignerEvaluationService.Scores(
                version,
                BigDecimal.valueOf(completion),
                n,
                n,
                n,
                "评价",
                BigDecimal.ZERO,
                "完成说明",
                "协作说明",
                "考勤说明",
                "周报说明",
                "扣分说明");
    }

    private void designer() {
        when(users.findByUserId("d1"))
                .thenReturn(Optional.of(User.builder()
                        .userId("d1")
                        .role("designer")
                        .status("active")
                        .build()));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var target = new MonthlyUserPointTarget();
        target.setTargetPoints(100);
        when(targets.findByMonthKeyAndUserId("PERMANENT", "d1")).thenReturn(Optional.of(target));
        when(performance.leaderboard("2026-10"))
                .thenReturn(java.util.List.of(java.util.Map.of("userId", "d1", "points", 100d)));
    }

    @Test
    void completionUsesMonthlyPointsAndDesignerTargetWithCap() {
        designer();
        var target = new MonthlyUserPointTarget();
        target.setTargetPoints(80);
        when(targets.findByMonthKeyAndUserId("2026-10", "d1")).thenReturn(Optional.of(target));
        assertEquals(
                new BigDecimal("125.0"), service.completion("2026-10", "d1").completionScore());
        assertEquals(
                new BigDecimal("117.50"),
                service.save("2026-10", "d1", scores(null, 0), "a1").totalScore());
        when(performance.leaderboard("2026-10"))
                .thenReturn(java.util.List.of(java.util.Map.of("userId", "d1", "points", 160d)));
        assertEquals(new BigDecimal("150"), service.completion("2026-10", "d1").completionScore());
    }

    @Test
    void missingTargetCannotBeSavedOrManuallyOverridden() {
        designer();
        when(targets.findByMonthKeyAndUserId("PERMANENT", "d1")).thenReturn(Optional.empty());
        assertNull(service.completion("2026-10", "d1").completionScore());
        assertThrows(IllegalArgumentException.class, () -> service.save("2026-10", "d1", scores(null, 100), "a1"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void staleVersionDoesNotOverwrite() {
        designer();
        var existing = new MonthlyDesignerEvaluation();
        existing.setVersion(2L);
        when(repository.findByUserIdAndMonthKey("d1", "2026-10")).thenReturn(Optional.of(existing));
        assertEquals(
                409,
                assertThrows(ResponseStatusException.class, () -> service.save("2026-10", "d1", scores(1L, 100), "a1"))
                        .getStatusCode()
                        .value());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidScoresAndInactiveUsers() {
        designer();
        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(
                        "2026-10",
                        "d1",
                        new MonthlyDesignerEvaluationService.Scores(
                                null, null, null, null, null, null, null, null, null, null, null, null),
                        "a1"));
        when(users.findByUserId("d1"))
                .thenReturn(Optional.of(
                        User.builder().role("designer").status("disabled").build()));
        assertThrows(IllegalArgumentException.class, () -> service.save("2026-10", "d1", scores(null, 100), "a1"));
    }

    @Test
    void strictPeriodValidationAndAnnualBoundaries() {
        for (String invalid : new String[] {"0000", "2026-13", "2026-1", "+2026", "20260", "2026-00"})
            assertThrows(IllegalArgumentException.class, () -> service.list(invalid));
        service.list("2026");
        verify(repository).findByMonthKeyBetweenOrderByMonthKeyAscUserIdAsc("2026-01", "2026-12");
        assertThrows(IllegalArgumentException.class, () -> service.save("2026", "d1", scores(null, 100), "a1"));
    }

    @Test
    void weeklyReportDeductionIsSeparateAndBounded() {
        designer();
        var n = BigDecimal.valueOf(100);
        for (String deduction : new String[] {"0", "5", "10"}) {
            var value = new BigDecimal(deduction);
            var request = new MonthlyDesignerEvaluationService.Scores(
                    null, n, n, n, n, null, value, "完成说明", "协作说明", "考勤说明", "周报说明", "扣分说明");
            assertEquals(
                    new BigDecimal("100.00").subtract(value),
                    service.save("2026-10", "d1", request, "a1").totalScore());
        }
        for (String deduction : new String[] {"-1", "1", "4.99", "10.01"}) {
            var request = new MonthlyDesignerEvaluationService.Scores(
                    null, n, n, n, n, null, new BigDecimal(deduction), "完成说明", "协作说明", "考勤说明", "周报说明", "扣分说明");
            assertThrows(IllegalArgumentException.class, () -> service.save("2026-10", "d1", request, "a1"));
        }
    }

    @Test
    void legacyAndUppercaseActiveDesignersCanBeEvaluated() {
        designer();
        for (String status : new String[] {null, "ACTIVE"}) {
            when(users.findByUserId("d1"))
                    .thenReturn(Optional.of(
                            User.builder().role("designer").status(status).build()));
            assertEquals(
                    new BigDecimal("100.00"),
                    service.save("2026-10", "d1", scores(null, 100), "a1").totalScore());
        }
    }

    @Test
    void automaticCompletionCannotBeOverriddenAndLegacyTotalsUseFourWeights() {
        designer();
        assertEquals(
                new BigDecimal("100.00"),
                service.save("2026-10", "d1", scores(null, 0), "a1").totalScore());
        var legacy = new MonthlyDesignerEvaluation();
        legacy.setCompletionScore(BigDecimal.valueOf(50));
        legacy.setCollaborationScore(BigDecimal.valueOf(100));
        legacy.setAttendanceScore(BigDecimal.valueOf(100));
        legacy.setWeeklyReportScore(BigDecimal.valueOf(100));
        legacy.setWeeklyReportDeduction(BigDecimal.ZERO);
        legacy.setTotalScore(BigDecimal.ZERO);
        when(repository.findByMonthKeyBetweenOrderByMonthKeyAscUserIdAsc("2026-10", "2026-10"))
                .thenReturn(java.util.List.of(legacy));
        assertEquals(new BigDecimal("65.00"), service.list("2026-10").getFirst().totalScore());
        assertEquals(BigDecimal.ZERO, legacy.getTotalScore());
    }

    private MonthlyDesignerEvaluationService.Scores reasons(
            String completion,
            String collaboration,
            String attendance,
            String weekly,
            String deduction,
            int deductionScore) {
        var n = BigDecimal.valueOf(100);
        return new MonthlyDesignerEvaluationService.Scores(
                null,
                n,
                n,
                n,
                n,
                null,
                BigDecimal.valueOf(deductionScore),
                completion,
                collaboration,
                attendance,
                weekly,
                deduction);
    }

    @Test
    void optionalExplanationsAreTrimmedBoundedAndEchoed() {
        designer();
        for (String invalid : new String[] {"x".repeat(2001)}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.save("2026-10", "d1", reasons("完成", invalid, "考勤", "周报", null, 0), "a1"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.save("2026-10", "d1", reasons("完成", "协作", invalid, "周报", null, 0), "a1"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.save("2026-10", "d1", reasons("完成", "协作", "考勤", invalid, null, 0), "a1"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.save("2026-10", "d1", reasons("完成", "协作", "考勤", "周报", invalid, 5), "a1"));
        }
        verify(repository, never()).saveAndFlush(any());
        for (String blank : new String[] {null, "", " \t\n　\u00a0"}) {
            var optional = service.save("2026-10", "d1", reasons(blank, blank, blank, blank, blank, 5), "a1");
            assertNotNull(optional.completionReason());
            assertNull(optional.collaborationReason());
            assertNull(optional.attendanceReason());
            assertNull(optional.weeklyReportReason());
            assertNull(optional.weeklyReportDeductionReason());
        }
        var saved = service.save("2026-10", "d1", reasons("　完成　", "协作", "考勤", "周报", null, 0), "a1");
        assertNotEquals("完成", saved.completionReason());
        assertEquals("协作", saved.collaborationReason());
        assertNull(saved.weeklyReportDeductionReason());
        assertEquals(
                "未交周报",
                service.save("2026-10", "d1", reasons("完成", "协作", "考勤", "周报", " 未交周报 ", 5), "a1")
                        .weeklyReportDeductionReason());
    }

    @Test
    void automaticReasonIsSnapshotAndIgnoresClientForgery() {
        designer();
        var automatic = service.completion("2026-10", "d1");
        var saved = service.save("2026-10", "d1", reasons("伪造", "协作", "考勤", "周报", null, 0), "a1");
        assertEquals(automatic.completionReason(), saved.completionReason());
        assertTrue(saved.completionReason().contains("100 ÷ 目标积分 100"));
    }

    @Test
    void clearingDeductionClearsOldReasonWithoutValidatingUnusedClientText() {
        designer();
        var old = new MonthlyDesignerEvaluation();
        old.setVersion(2L);
        old.setWeeklyReportDeduction(BigDecimal.valueOf(5));
        old.setWeeklyReportDeductionReason("之前未交周报");
        when(repository.findByUserIdAndMonthKey("d1", "2026-10")).thenReturn(Optional.of(old));
        var n = BigDecimal.valueOf(100);
        var request = new MonthlyDesignerEvaluationService.Scores(
                2L, n, n, n, n, null, BigDecimal.ZERO, "完成", "协作", "考勤", "周报", "旧理由".repeat(1000));
        var saved = service.save("2026-10", "d1", request, "a1");
        assertNull(saved.weeklyReportDeductionReason());
        assertNull(old.getWeeklyReportDeductionReason());
        assertEquals(new BigDecimal("0.00"), saved.weeklyReportDeduction());
    }
}
