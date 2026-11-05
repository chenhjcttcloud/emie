package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.MonthlyDesignerEvaluation;
import com.emie.designpm.entity.MonthlyUserPointTarget;
import com.emie.designpm.entity.User;
import com.emie.designpm.performance.repository.MonthlyDesignerEvaluationRepository;
import com.emie.designpm.performance.repository.MonthlyUserPointTargetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MonthlyDesignerEvaluationAuditTest {
    private final MonthlyDesignerEvaluationRepository evaluations = mock(MonthlyDesignerEvaluationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PerformanceService performance = mock(PerformanceService.class);
    private final MonthlyUserPointTargetRepository targets = mock(MonthlyUserPointTargetRepository.class);
    private final ActivityLogRepository logs = mock(ActivityLogRepository.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MonthlyDesignerEvaluationService service =
            new MonthlyDesignerEvaluationService(evaluations, users, performance, targets, logs, json);

    private void designer() {
        when(users.findByUserId("d1"))
                .thenReturn(Optional.of(User.builder()
                        .userId("d1")
                        .role("designer")
                        .status("active")
                        .build()));
        var target = new MonthlyUserPointTarget();
        target.setTargetPoints(100);
        when(targets.findByMonthKeyAndUserId("2026-10", "d1")).thenReturn(Optional.of(target));
        when(performance.leaderboard("2026-10")).thenReturn(List.of(Map.of("userId", "d1", "points", 100d)));
        when(evaluations.saveAndFlush(any())).thenAnswer(invocation -> {
            MonthlyDesignerEvaluation saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(42L);
            saved.setVersion(saved.getVersion() == null ? 0L : saved.getVersion() + 1);
            return saved;
        });
    }

    private MonthlyDesignerEvaluationService.Scores scores(Long version) {
        return new MonthlyDesignerEvaluationService.Scores(
                version,
                BigDecimal.ZERO,
                new BigDecimal("81"),
                new BigDecimal("92"),
                new BigDecimal("73"),
                "新评语",
                new BigDecimal("5"),
                "忽略客户端完成原因",
                "成长周报 OS · 360协同；版本=fixture-collaboration",
                "新考勤原因",
                "成长周报 OS · 周报质量；版本=fixture-quality",
                "周报迟交");
    }

    private ActivityLog capturedLog() {
        var captor = ArgumentCaptor.forClass(ActivityLog.class);
        verify(logs).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void updateAuditKeepsOriginalStoredTotalAndLegacyFieldsAlongsideNewSourceScores() throws Exception {
        designer();
        var old = new MonthlyDesignerEvaluation();
        old.setId(17L);
        old.setUserId("d1");
        old.setMonthKey("2026-10");
        old.setVersion(4L);
        old.setCompletionScore(new BigDecimal("50"));
        old.setCollaborationScore(new BigDecimal("60"));
        old.setAttendanceScore(new BigDecimal("70"));
        old.setWeeklyReportScore(new BigDecimal("80"));
        old.setWeeklyReportDeduction(new BigDecimal("10"));
        old.setTotalScore(new BigDecimal("31.25"));
        old.setAdjustmentScore(new BigDecimal("12"));
        old.setAestheticScore(new BigDecimal("23"));
        old.setInitiativeScore(new BigDecimal("34"));
        old.setComment("旧评语");
        old.setCollaborationReason("旧协作原因");
        old.setWeeklyReportReason("旧周报原因");
        old.setUpdatedBy("old-admin");
        old.setUpdatedAt(LocalDateTime.of(2026, 10, 31, 20, 0));
        when(evaluations.findByUserIdAndMonthKey("d1", "2026-10")).thenReturn(Optional.of(old));

        var result = service.save("2026-10", "d1", scores(4L), "a1");
        var log = capturedLog();
        assertEquals("monthly_designer_evaluation", log.getEntityType());
        assertEquals(17L, log.getEntityId());
        assertEquals("a1", log.getUsername());
        assertEquals("admin", log.getRole());
        var before = json.readTree(log.getBeforeData());
        var after = json.readTree(log.getAfterData());
        assertEquals(new BigDecimal("31.25"), before.path("totalScore").decimalValue());
        assertEquals(4L, before.path("version").longValue());
        assertEquals("old-admin", before.path("updatedBy").textValue());
        assertEquals("旧评语", before.path("comment").textValue());
        assertEquals("旧周报原因", before.path("weeklyReportReason").textValue());
        assertEquals(60, before.path("collaborationScore").intValue());
        assertEquals(80, before.path("weeklyReportScore").intValue());
        for (var field : List.of("adjustmentScore", "aestheticScore", "initiativeScore"))
            assertEquals(before.path(field), after.path(field));
        assertEquals(12, before.path("adjustmentScore").intValue());
        assertEquals(23, before.path("aestheticScore").intValue());
        assertEquals(34, before.path("initiativeScore").intValue());
        assertEquals(100, after.path("completionScore").intValue());
        assertEquals(81, after.path("collaborationScore").intValue());
        assertEquals(73, after.path("weeklyReportScore").intValue());
        assertEquals(92, after.path("attendanceScore").intValue());
        assertEquals(5, after.path("weeklyReportDeduction").intValue());
        assertEquals(
                0, new BigDecimal("89.60").compareTo(after.path("totalScore").decimalValue()));
        assertEquals(5L, after.path("version").longValue());
        assertEquals("a1", after.path("updatedBy").textValue());
        assertTrue(after.path("collaborationReason").textValue().contains("fixture-collaboration"));
        assertTrue(after.path("weeklyReportReason").textValue().contains("fixture-quality"));
        assertEquals(new BigDecimal("89.60"), result.totalScore());
        assertEquals("旧周报原因", before.path("weeklyReportReason").textValue());
    }

    @Test
    void createAuditHasNoBeforeSnapshotAndStoresNewScoresWithZeroLegacyFields() throws Exception {
        designer();
        when(evaluations.findByUserIdAndMonthKey("d1", "2026-10")).thenReturn(Optional.empty());
        service.save("2026-10", "d1", scores(null), "a1");
        var log = capturedLog();
        assertNull(log.getBeforeData());
        assertEquals(42L, log.getEntityId());
        var after = json.readTree(log.getAfterData());
        assertEquals("d1", after.path("userId").textValue());
        assertEquals("2026-10", after.path("monthKey").textValue());
        assertEquals(0L, after.path("version").longValue());
        assertEquals(81, after.path("collaborationScore").intValue());
        assertEquals(73, after.path("weeklyReportScore").intValue());
        for (var field : List.of("adjustmentScore", "aestheticScore", "initiativeScore"))
            assertEquals(0, after.path(field).intValue());
        assertTrue(after.path("completionReason").textValue().contains("目标积分 100"));
        assertTrue(after.path("weeklyReportReason").textValue().contains("fixture-quality"));
    }
}
