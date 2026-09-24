package com.emie.designpm.points.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.PointRule;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PointsServiceSnapshotTest {

    @Test
    void bindsEnabledRuleAndAwardsFromSnapshotAfterRuleChanges() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointRule rule = rule("B1", 20, true);
        when(rules.findByRuleCode("B1")).thenReturn(Optional.of(rule));
        ScoringRecord score = new ScoringRecord();
        score.setScore(96);
        when(scoring.findBySubTaskId(9L)).thenReturn(List.of(score));
        rule.setQualityBonusThreshold(95);
        rule.setQualityBonusRatio(0.5);
        rule.setCountInPerformance(false);
        PointsService service = new PointsService(rules, ledgers, scoring);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");
        task.setAssigneeRole("designer");

        service.bindRuleSnapshot(task, "b1");
        rule.setPoints(100);
        rule.setQualityBonusThreshold(100);
        rule.setQualityBonusRatio(0d);
        rule.setCountInPerformance(true);
        service.awardTaskApproval(task);

        assertEquals("B1", task.getPointRuleCode());
        assertEquals(20, task.getBasePointSnapshot());
        assertEquals(1d, task.getDifficultyMultiplierSnapshot());
        assertEquals(95, task.getQualityBonusThresholdSnapshot());
        assertEquals(0.5, task.getQualityBonusRatioSnapshot());
        assertEquals(false, task.getCountInPerformanceSnapshot());
        verify(ledgers)
                .save(argThat(ledger -> ledger.getPoints() == 20
                        && ledger.isCountInPerformance()
                        && "B1:BASE".equals(ledger.getRuleCode())));
        verify(ledgers)
                .save(argThat(ledger -> ledger.getPoints() == 10
                        && ledger.isCountInPerformance()
                        && "B1:QUALITY".equals(ledger.getRuleCode())));
    }

    @Test
    void legacyCollaborationSnapshotNoLongerSplitsDesignerPoints() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointsService service =
                new PointsService(mock(PointRuleRepository.class), ledgers, mock(ScoringRepository.class));
        SubTask task = new SubTask();
        task.setId(10L);
        task.setDesignerId("main");
        task.setAssigneeRole("designer");
        task.setPointRuleCode("B1");
        task.setBasePointSnapshot(25);
        task.setDifficultyMultiplierSnapshot(1.5);
        task.setQualityBonusThresholdSnapshot(95);
        task.setQualityBonusRatioSnapshot(.3);
        task.setQualityTopThresholdSnapshot(97);
        task.setQualityTopRatioSnapshot(.6);
        task.setMaxTotalMultiplierSnapshot(3d);
        task.setCountInPerformanceSnapshot(true);
        task.setMilestoneMonth("2026-09");
        task.setCollaboratorAllocationsJson("[{\"userId\":\"partner\",\"name\":\"协作者\",\"ratio\":30}]");

        service.awardBaseSubmission(task);

        ArgumentCaptor<PointLedger> captor = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgers).save(captor.capture());
        assertEquals(37.5, captor.getValue().getPoints(), .001);
        assertEquals("main", captor.getValue().getUserId());
        assertEquals("2026-09", captor.getValue().getAccountingMonth());
    }

    @Test
    void taskWithoutPointRuleDoesNotAwardPoints() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointsService service = new PointsService(mock(PointRuleRepository.class), ledgers, scoring);
        SubTask task = new SubTask();
        task.setId(10L);
        task.setDesignerId("designer-1");
        task.setAssigneeRole("designer");

        service.awardBaseSubmission(task);
        service.awardQualityCompletion(task);

        verifyNoInteractions(ledgers, scoring);
    }

    @Test
    void designerSubmissionAwardsBasePointsEvenForTaskCreatedBeforeFormerStartDate() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointsService service = new PointsService(
                mock(PointRuleRepository.class),
                ledgers,
                mock(ScoringRepository.class),
                mock(com.emie.designpm.points.repository.PointAdjustmentLedgerRepository.class),
                mock(SystemConfigRepository.class));
        SubTask task = taskWithRole("designer");
        task.setCreatedAt(LocalDateTime.parse("2026-08-01T10:00:00"));

        service.awardBaseSubmission(task);

        verify(ledgers).save(argThat(ledger -> "B1:BASE".equals(ledger.getRuleCode()) && ledger.getPoints() == 30));
    }

    @Test
    void disabledRuleCannotBeBound() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointRule rule = rule("A1", 10, false);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule));
        PointsService service =
                new PointsService(rules, mock(PointLedgerRepository.class), mock(ScoringRepository.class));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> service.bindRuleSnapshot(new SubTask(), "A1"));

        assertEquals("积分规则已停用，请重新选择", error.getMessage());
        verify(rules, never()).save(any());
    }

    @Test
    void newRuleSnapshotDoesNotBindDifficultyTier() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule("A1", 10, true)));
        PointsService service =
                new PointsService(rules, mock(PointLedgerRepository.class), mock(ScoringRepository.class));

        SubTask task = new SubTask();
        service.bindRuleSnapshot(task, "A1");

        assertEquals(1d, task.getDifficultyMultiplierSnapshot());
    }

    @Test
    void adminCanCreateAndRemoveCustomRule() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointsService service =
                new PointsService(rules, mock(PointLedgerRepository.class), mock(ScoringRepository.class));
        PointRule input = rule("custom_1", 12, true);
        when(rules.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PointRule saved = service.createRule(input);
        assertEquals("CUSTOM_1", saved.getRuleCode());

        when(rules.findByRuleCode("CUSTOM_1")).thenReturn(Optional.of(saved));
        service.deleteRule("custom_1");
        verify(rules).delete(saved);
    }

    @Test
    void qualityThresholdUsesWeightedAverageMatchingPageDisplay() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointRule rule = rule("A1", 20, true);
        rule.setQualityBonusThreshold(89);
        rule.setQualityBonusRatio(0.5);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule));

        // 加权综合 = (80×0.6 + 100×0.4) / (0.6+0.4) = 88；简单平均 = 90。
        // 阈值 89：按页面展示的加权算法不应发质量加分（简单平均则会误发）。
        ScoringRecord planner = new ScoringRecord();
        planner.setScore(80);
        planner.setWeight(0.6);
        ScoringRecord admin = new ScoringRecord();
        admin.setScore(100);
        admin.setWeight(0.4);
        when(scoring.findBySubTaskId(9L)).thenReturn(List.of(planner, admin));

        PointsService service = new PointsService(rules, ledgers, scoring);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");
        task.setAssigneeRole("designer");
        service.bindRuleSnapshot(task, "A1");

        service.awardTaskApproval(task);

        verify(ledgers).save(argThat(ledger -> "A1:BASE".equals(ledger.getRuleCode())));
        verify(ledgers, never()).save(argThat(ledger -> "A1:QUALITY".equals(ledger.getRuleCode())));
    }

    @Test
    void qualityThresholdAwardsWhenWeightedAverageMeetsThreshold() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointRule rule = rule("A1", 20, true);
        rule.setQualityBonusThreshold(88);
        rule.setQualityBonusRatio(0.5);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule));

        // 加权综合 = 88，阈值 88：达到阈值应发质量加分（基础分 20 × 比例 0.5 = 10）。
        ScoringRecord planner = new ScoringRecord();
        planner.setScore(80);
        planner.setWeight(0.6);
        ScoringRecord admin = new ScoringRecord();
        admin.setScore(100);
        admin.setWeight(0.4);
        when(scoring.findBySubTaskId(9L)).thenReturn(List.of(planner, admin));

        PointsService service = new PointsService(rules, ledgers, scoring);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");
        task.setAssigneeRole("designer");
        service.bindRuleSnapshot(task, "A1");

        service.awardTaskApproval(task);

        verify(ledgers).save(argThat(ledger -> "A1:QUALITY".equals(ledger.getRuleCode()) && ledger.getPoints() == 10d));
    }

    @Test
    void crossMonthCompletionKeepsBaseInBookingMonthAndQualityInCompletionMonth() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointRule rule = rule("B1", 20, true);
        rule.setQualityBonusThreshold(95);
        rule.setQualityBonusRatio(0.5);
        rule.setQualityTopThreshold(97);
        rule.setQualityTopRatio(0.6);
        rule.setMaxTotalMultiplier(3d);
        when(rules.findByRuleCode("B1")).thenReturn(Optional.of(rule));
        ScoringRecord score = new ScoringRecord();
        score.setScore(96);
        when(scoring.findBySubTaskId(9L)).thenReturn(List.of(score));

        // 6 月送审入账的 BASE：送审时 actualDate 为空，按里程碑月锁定为 2026-06。
        PointLedger base = new PointLedger();
        base.setUserId("designer-1");
        base.setSubTaskId(9L);
        base.setRuleCode("B1:BASE");
        base.setPoints(30d);
        base.setAccountingMonth("2026-06");
        when(ledgers.findBySubTaskId(9L)).thenReturn(List.of(base));
        when(ledgers.existsByUserIdAndSubTaskIdAndRuleCode("designer-1", 9L, "B1:BASE"))
                .thenReturn(true);
        when(ledgers.existsByUserIdAndSubTaskIdAndRuleCode("designer-1", 9L, "B1:QUALITY"))
                .thenReturn(false);

        PointsService service = new PointsService(rules, ledgers, scoring);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");
        task.setAssigneeRole("designer");
        task.setPointRuleCode("B1");
        task.setBasePointSnapshot(20);
        task.setDifficultyMultiplierSnapshot(1.5);
        task.setQualityBonusThresholdSnapshot(95);
        task.setQualityBonusRatioSnapshot(0.5);
        task.setQualityTopThresholdSnapshot(97);
        task.setQualityTopRatioSnapshot(0.6);
        task.setMaxTotalMultiplierSnapshot(3d);
        task.setCountInPerformanceSnapshot(true);
        task.setMilestoneMonth("2026-06");
        // 7 月最终验收：actualDate 在完成节点写入。
        task.setActualDate("2026-07-15");

        service.awardQualityCompletion(task);

        // P1-3：不得回溯改写既有流水——BASE 仍归属送审入账月 2026-06，且不再发起改写查询。
        assertEquals("2026-06", base.getAccountingMonth());
        verify(ledgers, never()).findBySubTaskId(any());
        // 新增 QUALITY 按实际完成月归属 2026-07，与 BASE 分属各自入账月，月度统计无重复错位。
        verify(ledgers)
                .save(argThat(ledger ->
                        "B1:QUALITY".equals(ledger.getRuleCode()) && "2026-07".equals(ledger.getAccountingMonth())));
    }

    @Test
    void supplyChainTaskGetsNoBaseOrQualityAward() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointsService service =
                new PointsService(mock(PointRuleRepository.class), ledgers, mock(ScoringRepository.class));
        SubTask task = taskWithRole("supplychain");

        service.awardBaseSubmission(task);
        service.awardQualityCompletion(task);

        verify(ledgers, never()).save(any());
        verify(ledgers, never()).existsByUserIdAndSubTaskIdAndRuleCode(any(), any(), any());
    }

    @Test
    void nullRoleTaskGetsNoBaseOrQualityAward() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointsService service =
                new PointsService(mock(PointRuleRepository.class), ledgers, mock(ScoringRepository.class));
        SubTask task = taskWithRole(null);

        service.awardBaseSubmission(task);
        service.awardQualityCompletion(task);

        verify(ledgers, never()).save(any());
    }

    private SubTask taskWithRole(String role) {
        SubTask task = new SubTask();
        task.setId(10L);
        task.setDesignerId("designer-1");
        task.setAssigneeRole(role);
        task.setPointRuleCode("B1");
        task.setBasePointSnapshot(20);
        task.setDifficultyMultiplierSnapshot(1.5);
        task.setQualityBonusThresholdSnapshot(95);
        task.setQualityBonusRatioSnapshot(0.5);
        task.setQualityTopThresholdSnapshot(97);
        task.setQualityTopRatioSnapshot(0.6);
        task.setMaxTotalMultiplierSnapshot(3d);
        task.setCountInPerformanceSnapshot(true);
        return task;
    }

    private PointRule rule(String code, int points, boolean enabled) {
        PointRule rule = new PointRule();
        rule.setRuleCode(code);
        rule.setPoints(points);
        rule.setEnabled(enabled);
        rule.setQualityBonusThreshold(0);
        rule.setQualityBonusRatio(0d);
        return rule;
    }
}
