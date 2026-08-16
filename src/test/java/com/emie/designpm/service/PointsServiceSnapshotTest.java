package com.emie.designpm.service;

import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.PointDifficultyConfig;
import com.emie.designpm.entity.PointRule;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.PointLedgerRepository;
import com.emie.designpm.repository.PointDifficultyConfigRepository;
import com.emie.designpm.repository.PointRuleRepository;
import com.emie.designpm.repository.ScoringRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class PointsServiceSnapshotTest {

    @Test
    void bindsEnabledRuleAndAwardsFromSnapshotAfterRuleChanges() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointDifficultyConfigRepository difficulties = mock(PointDifficultyConfigRepository.class);
        PointRule rule = rule("B1", 20, 1.5, true);
        when(rules.findByRuleCode("B1")).thenReturn(Optional.of(rule));
        PointDifficultyConfig complex = difficulty("COMPLEX", 1.5, true);
        when(difficulties.findByDifficultyCode("COMPLEX")).thenReturn(Optional.of(complex));
        ScoringRecord score = new ScoringRecord();
        score.setScore(96);
        when(scoring.findBySubTaskId(9L)).thenReturn(List.of(score));
        rule.setQualityBonusThreshold(95);
        rule.setQualityBonusRatio(0.5);
        rule.setCountInPerformance(false);
        PointsService service = new PointsService(rules, ledgers, scoring, difficulties);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");

        service.bindRuleSnapshot(task, "b1", "standard");
        rule.setPoints(100);
        rule.setDifficultyMultiplier(2d);
        rule.setQualityBonusThreshold(100);
        rule.setQualityBonusRatio(0d);
        rule.setCountInPerformance(true);
        complex.setMultiplier(3d);
        service.awardTaskApproval(task);

        assertEquals("B1", task.getPointRuleCode());
        assertEquals("COMPLEX", task.getDifficultyCode());
        assertEquals(20, task.getBasePointSnapshot());
        assertEquals(1.5, task.getDifficultyMultiplierSnapshot());
        assertEquals(95, task.getQualityBonusThresholdSnapshot());
        assertEquals(0.5, task.getQualityBonusRatioSnapshot());
        assertEquals(false, task.getCountInPerformanceSnapshot());
        verify(ledgers).save(argThat(ledger -> ledger.getPoints() == 30
                && ledger.isCountInPerformance() && "B1:BASE".equals(ledger.getRuleCode())));
        verify(ledgers).save(argThat(ledger -> ledger.getPoints() == 15
                && ledger.isCountInPerformance() && "B1:QUALITY".equals(ledger.getRuleCode())));
    }

    @Test
    void collaborationKeepsDecimalTotalAndMilestoneMonth() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointsService service = new PointsService(mock(PointRuleRepository.class), ledgers,
                mock(ScoringRepository.class), mock(PointDifficultyConfigRepository.class));
        SubTask task = new SubTask(); task.setId(10L); task.setDesignerId("main");
        task.setPointRuleCode("B1"); task.setBasePointSnapshot(25); task.setDifficultyMultiplierSnapshot(1.5);
        task.setQualityBonusThresholdSnapshot(95); task.setQualityBonusRatioSnapshot(.3);
        task.setQualityTopThresholdSnapshot(97); task.setQualityTopRatioSnapshot(.6); task.setMaxTotalMultiplierSnapshot(3d);
        task.setCountInPerformanceSnapshot(true); task.setMilestoneMonth("2026-09");
        task.setCollaboratorAllocationsJson("[{\"userId\":\"partner\",\"name\":\"协作者\",\"ratio\":30}]");

        service.awardBaseSubmission(task);

        ArgumentCaptor<PointLedger> captor = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgers, times(2)).save(captor.capture());
        assertEquals(37.5, captor.getAllValues().stream().mapToDouble(PointLedger::getPoints).sum(), .001);
        assertEquals(11.3, captor.getAllValues().stream().filter(item -> "partner".equals(item.getUserId())).findFirst().orElseThrow().getPoints(), .001);
        captor.getAllValues().forEach(item -> assertEquals("2026-09", item.getAccountingMonth()));
    }

    @Test
    void disabledRuleCannotBeBound() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointRule rule = rule("A1", 10, 1d, false);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule));
        PointsService service = new PointsService(rules, mock(PointLedgerRepository.class),
                mock(ScoringRepository.class), mock(PointDifficultyConfigRepository.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.bindRuleSnapshot(new SubTask(), "A1", "STANDARD"));

        assertEquals("积分规则已停用，请重新选择", error.getMessage());
        verify(rules, never()).save(any());
    }

    @Test
    void disabledDifficultyCannotBeBound() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointDifficultyConfigRepository difficulties = mock(PointDifficultyConfigRepository.class);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule("A1", 10, 1d, true)));
        when(difficulties.findByDifficultyCode("MAJOR"))
                .thenReturn(Optional.of(difficulty("MAJOR", 2d, false)));
        PointsService service = new PointsService(rules, mock(PointLedgerRepository.class),
                mock(ScoringRepository.class), difficulties);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.bindRuleSnapshot(new SubTask(), "A1", "MAJOR"));

        assertEquals("难度配置已停用，请重新选择", error.getMessage());
    }

    @Test
    void adminCanCreateAndRemoveCustomRule() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointsService service = new PointsService(rules, mock(PointLedgerRepository.class), mock(ScoringRepository.class));
        PointRule input = rule("custom_1", 12, 1d, true);
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
        PointDifficultyConfigRepository difficulties = mock(PointDifficultyConfigRepository.class);
        PointRule rule = rule("A1", 20, 1d, true);
        rule.setQualityBonusThreshold(89);
        rule.setQualityBonusRatio(0.5);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule));
        when(difficulties.findByDifficultyCode("STANDARD")).thenReturn(Optional.of(difficulty("STANDARD", 1d, true)));

        // 加权综合 = (80×0.6 + 100×0.4) / (0.6+0.4) = 88；简单平均 = 90。
        // 阈值 89：按页面展示的加权算法不应发质量加分（简单平均则会误发）。
        ScoringRecord planner = new ScoringRecord();
        planner.setScore(80);
        planner.setWeight(0.6);
        ScoringRecord admin = new ScoringRecord();
        admin.setScore(100);
        admin.setWeight(0.4);
        when(scoring.findBySubTaskId(9L)).thenReturn(List.of(planner, admin));

        PointsService service = new PointsService(rules, ledgers, scoring, difficulties);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");
        service.bindRuleSnapshot(task, "A1", "standard");

        service.awardTaskApproval(task);

        verify(ledgers).save(argThat(ledger -> "A1:BASE".equals(ledger.getRuleCode())));
        verify(ledgers, never()).save(argThat(ledger -> "A1:QUALITY".equals(ledger.getRuleCode())));
    }

    @Test
    void qualityThresholdAwardsWhenWeightedAverageMeetsThreshold() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointDifficultyConfigRepository difficulties = mock(PointDifficultyConfigRepository.class);
        PointRule rule = rule("A1", 20, 1d, true);
        rule.setQualityBonusThreshold(88);
        rule.setQualityBonusRatio(0.5);
        when(rules.findByRuleCode("A1")).thenReturn(Optional.of(rule));
        when(difficulties.findByDifficultyCode("STANDARD")).thenReturn(Optional.of(difficulty("STANDARD", 1d, true)));

        // 加权综合 = 88，阈值 88：达到阈值应发质量加分（基础分 20 × 比例 0.5 = 10）。
        ScoringRecord planner = new ScoringRecord();
        planner.setScore(80);
        planner.setWeight(0.6);
        ScoringRecord admin = new ScoringRecord();
        admin.setScore(100);
        admin.setWeight(0.4);
        when(scoring.findBySubTaskId(9L)).thenReturn(List.of(planner, admin));

        PointsService service = new PointsService(rules, ledgers, scoring, difficulties);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");
        service.bindRuleSnapshot(task, "A1", "standard");

        service.awardTaskApproval(task);

        verify(ledgers).save(argThat(ledger -> "A1:QUALITY".equals(ledger.getRuleCode())
                && ledger.getPoints() == 10d));
    }

    @Test
    void crossMonthCompletionKeepsBaseInBookingMonthAndQualityInCompletionMonth() {
        PointRuleRepository rules = mock(PointRuleRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        PointDifficultyConfigRepository difficulties = mock(PointDifficultyConfigRepository.class);
        PointRule rule = rule("B1", 20, 1.5, true);
        rule.setQualityBonusThreshold(95);
        rule.setQualityBonusRatio(0.5);
        rule.setQualityTopThreshold(97);
        rule.setQualityTopRatio(0.6);
        rule.setMaxTotalMultiplier(3d);
        when(rules.findByRuleCode("B1")).thenReturn(Optional.of(rule));
        when(difficulties.findByDifficultyCode("COMPLEX")).thenReturn(Optional.of(difficulty("COMPLEX", 1.5, true)));
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
        when(ledgers.existsByUserIdAndSubTaskIdAndRuleCode("designer-1", 9L, "B1:BASE")).thenReturn(true);
        when(ledgers.existsByUserIdAndSubTaskIdAndRuleCode("designer-1", 9L, "B1:QUALITY")).thenReturn(false);

        PointsService service = new PointsService(rules, ledgers, scoring, difficulties);
        SubTask task = new SubTask();
        task.setId(9L);
        task.setDesignerId("designer-1");
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
        verify(ledgers).save(argThat(ledger -> "B1:QUALITY".equals(ledger.getRuleCode())
                && "2026-07".equals(ledger.getAccountingMonth())));
    }

    private PointRule rule(String code, int points, double multiplier, boolean enabled) {
        PointRule rule = new PointRule();
        rule.setRuleCode(code);
        rule.setPoints(points);
        rule.setDifficultyMultiplier(multiplier);
        rule.setEnabled(enabled);
        rule.setQualityBonusThreshold(0);
        rule.setQualityBonusRatio(0d);
        return rule;
    }

    private PointDifficultyConfig difficulty(String code, double multiplier, boolean enabled) {
        PointDifficultyConfig difficulty = new PointDifficultyConfig();
        difficulty.setDifficultyCode(code);
        difficulty.setMultiplier(multiplier);
        difficulty.setEnabled(enabled);
        return difficulty;
    }
}
