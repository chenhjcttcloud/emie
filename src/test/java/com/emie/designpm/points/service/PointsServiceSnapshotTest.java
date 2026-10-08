package com.emie.designpm.points.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.PointRule;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PointsServiceSnapshotTest {
    private final PointRuleRepository rules = mock(PointRuleRepository.class);
    private final PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
    private final ScoringRepository scoring = mock(ScoringRepository.class);
    private final PointsService service = new PointsService(rules, ledgers, scoring);

    private SubTask task() {
        PointRule rule = new PointRule();
        rule.setRuleCode("D30_6");
        rule.setPoints(2.5d);
        when(rules.findByRuleCode("D30_6")).thenReturn(Optional.of(rule));
        SubTask task = new SubTask();
        task.setId(90L);
        task.setDesignerId("designer-1");
        task.setAssigneeRole("designer");
        service.bindRuleSnapshot(task, "D30_6");
        return task;
    }

    @Test
    void submissionAndRejectionNeverAwardAndConfirmationOnlyUsesFrozenBase() {
        SubTask task = task();
        assertEquals(1d, task.getDifficultyMultiplierSnapshot());
        task.setStatus("submitted_for_review");
        service.awardBaseSubmission(task);
        task.setStatus("rejected");
        service.awardBaseSubmission(task);
        verify(ledgers, never()).save(any());
        task.setStatus("completed");
        task.setDifficultyMultiplierSnapshot(1.5d); // Legacy snapshot values no longer affect awards.
        service.awardTaskApproval(task);
        verify(ledgers).save(argThat(l -> l.getPoints() == 2.5d && "D30_6:BASE".equals(l.getRuleCode())));
        verifyNoInteractions(scoring);
    }

    @Test
    void existingBaseLedgerIsNotChangedOrAwardedAgain() {
        SubTask task = task();
        task.setStatus("completed");
        when(ledgers.existsByUserIdAndSubTaskIdAndRuleCode("designer-1", 90L, "D30_6:BASE"))
                .thenReturn(true);
        service.awardBaseSubmission(task);
        verify(ledgers, never()).save(any());
    }

    @Test
    void paidRevisionKeepsOriginalSnapshotAndBooksSelectedRuleOnlyAfterConfirmation() {
        SubTask task = task();
        task.setPendingChangeBonusRequestId("request-1");
        task.setPendingChangeRuleCode("D30_8");
        task.setPendingChangeBonusPoints(15d);
        task.setPendingChangeBonusReason("新增设计要求");
        task.setStatus("submitted_for_review");
        service.awardPendingChangeBonus(task);
        verify(ledgers, never()).save(any());
        assertEquals("request-1", task.getPendingChangeBonusRequestId());
        task.setStatus("completed");
        service.awardPendingChangeBonus(task);
        verify(ledgers).save(argThat(l -> l.getPoints() == 15d && "D30_8:CHANGE:request-1".equals(l.getRuleCode())));
        assertEquals("D30_6", task.getPointRuleCode());
        assertEquals(2.5d, task.getBasePointSnapshot());
        assertNull(task.getPendingChangeBonusRequestId());
        service.awardPendingChangeBonus(task);
        verify(ledgers, times(1)).save(any(PointLedger.class));
    }

    @Test
    void revisionCanAwardSelectedRuleWhenOriginalTaskHadNoRule() {
        SubTask task = task();
        task.setPointRuleCode(null);
        task.setStatus("completed");
        task.setPendingChangeBonusRequestId("paid-round");
        task.setPendingChangeRuleCode("D30_8");
        task.setPendingChangeBonusPoints(15d);
        service.awardPendingChangeBonus(task);
        verify(ledgers).save(argThat(l -> "D30_8:CHANGE:paid-round".equals(l.getRuleCode()) && l.getPoints() == 15d));
    }

    @Test
    void unpaidRevisionClearsPendingRoundWithoutAward() {
        SubTask task = task();
        task.setStatus("completed");
        task.setPointRuleCode(null);
        task.setPendingChangeBonusRequestId("unpaid-round");
        task.setPendingChangeBonusPoints(0d);
        service.awardPendingChangeBonus(task);
        verify(ledgers, never()).save(any());
        assertNull(task.getPendingChangeBonusRequestId());
    }

    @Test
    void conceptsHaveNoMonthlyCapAndNonDesignersDoNotAward() {
        SubTask task = task();
        task.setPointRuleCode("D20_27");
        task.setBasePointSnapshot(30d);
        task.setStatus("completed");
        service.awardBaseSubmission(task);
        verify(ledgers).save(argThat(l -> l.getPoints() == 30d));
        task.setAssigneeRole("supplychain");
        service.awardBaseSubmission(task);
        verify(ledgers, times(1)).save(any());
    }

    @Test
    void disabledRuleCannotBeSelected() {
        PointRule rule = new PointRule();
        rule.setEnabled(false);
        when(rules.findByRuleCode("DISABLED")).thenReturn(Optional.of(rule));
        assertThrows(IllegalArgumentException.class, () -> service.bindRuleSnapshot(new SubTask(), "DISABLED"));
    }

    @Test
    void legacyManualApprovalLinksPointsToDeliveryAndRecordsZeroScores() {
        SubTask task = task();
        task.setName("旧任务");
        task.setMilestoneMonth("2026-09");
        SubTaskDeliveryVersion version = new SubTaskDeliveryVersion();
        version.setId(7L);
        version.setSubTask(task);
        version.setSubmittedAt(java.time.LocalDateTime.of(2026, 9, 30, 10, 0));
        version.setConfirmedAt(java.time.LocalDateTime.of(2026, 10, 2, 10, 0));

        service.awardLegacyManualApproval(task, version, 0d, "planner-1");

        assertEquals(0d, version.getExpectedPoints());
        assertEquals("LEGACY_MANUAL", version.getPointRuleCode());
        verify(ledgers)
                .save(argThat(ledger -> ledger.getPoints() == 0d
                        && "LEGACY_MANUAL:APPROVAL".equals(ledger.getRuleCode())
                        && Long.valueOf(7L).equals(ledger.getDeliveryVersionId())
                        && "2026-09".equals(ledger.getAccountingMonth())
                        && "planner-1".equals(ledger.getCreatedBy())));
    }
}
