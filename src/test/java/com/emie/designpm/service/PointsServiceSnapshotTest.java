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
