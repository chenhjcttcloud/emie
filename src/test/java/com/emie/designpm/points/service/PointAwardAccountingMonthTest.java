package com.emie.designpm.points.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PointAwardAccountingMonthTest {
    private final PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
    private final PointsService service =
            new PointsService(mock(PointRuleRepository.class), ledgers, mock(ScoringRepository.class));

    @Test
    void separateLegacyApprovalsBelongToTheirConfirmationMonths() {
        var task = task();
        var october = version(7, task, "2026-10-20T10:00:00", 3d);
        var november = version(8, task, "2026-11-20T10:00:00", 2d);

        service.awardLegacyManualApproval(task, october, 3d, "planner-1");
        service.awardLegacyManualApproval(task, november, 2d, "planner-1");

        var awards = saved(2);
        assertEquals("2026-10", awards.get(0).getAccountingMonth());
        assertEquals("2026-11", awards.get(1).getAccountingMonth());
        assertEquals(3d, awards.get(0).getPoints());
        assertEquals(2d, awards.get(1).getPoints());
        assertEquals("2026-09", task.getMilestoneMonth());
        assertEquals("LEGACY_MANUAL:APPROVAL:7", awards.get(0).getRuleCode());
        assertEquals("LEGACY_MANUAL:APPROVAL:8", awards.get(1).getRuleCode());
    }

    @Test
    void baseAndLaterRevisionUseEachVersionConfirmationRatherThanFinalCompletion() {
        var task = task();
        var october = version(7, task, "2026-10-20T10:00:00", 2.5d);
        service.awardBaseSubmission(task, october, "planner-1");
        task.setPendingChangeBonusRequestId("november-change");
        task.setPendingChangeRuleCode("D30_8");
        task.setPendingChangeBonusPoints(1.5d);
        var november = version(8, task, "2026-11-20T10:00:00", 1.5d);
        november.setPointRuleCode("D30_8");
        november.setPointRequestId("november-change");
        service.awardPendingChangeBonus(task, november, "planner-1");

        var awards = saved(2);
        assertEquals("2026-10", awards.get(0).getAccountingMonth());
        assertEquals("2026-11", awards.get(1).getAccountingMonth());
        assertEquals(LocalDateTime.parse("2026-10-20T10:00:00"), awards.get(0).getConfirmedAt());
        assertEquals("D30_6:BASE", awards.get(0).getRuleCode());
        assertEquals("D30_8:CHANGE:november-change", awards.get(1).getRuleCode());
        assertEquals(LocalDateTime.parse("2026-11-11T12:00:00"), task.getCompletedAt());
        assertNull(task.getPendingChangeBonusRequestId());
    }

    @Test
    void absentConfirmationFallsBackToBookingMonthForLegacyAndBaseAwards() {
        var task = task();
        task.setActualDate("2020-01-15");
        task.setCompletedAt(LocalDateTime.parse("2020-01-15T12:00:00"));
        var version = version(7, task, null, 2.5d);
        String before = YearMonth.now().toString();
        service.awardLegacyManualApproval(task, version, 1d, "planner-1");
        service.awardBaseSubmission(task, null, "planner-1");
        String after = YearMonth.now().toString();

        for (var ledger : saved(2)) {
            assertTrue(List.of(before, after).contains(ledger.getAccountingMonth()));
            assertNotEquals("2026-09", ledger.getAccountingMonth());
        }
    }

    @Test
    void baseVersionWithoutConfirmationAlsoFallsBackToBookingMonth() {
        var task = task();
        task.setActualDate("2020-01-15");
        task.setCompletedAt(LocalDateTime.parse("2020-01-15T12:00:00"));
        String before = YearMonth.now().toString();
        service.awardBaseSubmission(task, version(7, task, null, 2.5d), "planner-1");
        String after = YearMonth.now().toString();
        assertTrue(List.of(before, after).contains(saved(1).getFirst().getAccountingMonth()));
    }

    @Test
    void taskLedgerFillsMissingMonthFromConfirmationThenBookingWithoutOverwritingHistory() throws Exception {
        for (String missing : Arrays.asList(null, "", "  ")) {
            var confirmed = new PointLedger();
            confirmed.setAccountingMonth(missing);
            confirmed.setCreatedAt(LocalDateTime.parse("2026-11-01T09:00:00"));
            confirmed.setConfirmedAt(LocalDateTime.parse("2026-10-20T10:00:00"));
            persist(confirmed);
            assertEquals("2026-10", confirmed.getAccountingMonth());
            assertEquals(LocalDateTime.parse("2026-11-01T09:00:00"), confirmed.getCreatedAt());

            var booked = new PointLedger();
            booked.setAccountingMonth(missing);
            booked.setCreatedAt(LocalDateTime.parse("2026-09-22T09:00:00"));
            persist(booked);
            assertEquals("2026-09", booked.getAccountingMonth());
        }
        var historical = new PointLedger();
        historical.setAccountingMonth("2026-08");
        historical.setCreatedAt(LocalDateTime.parse("2026-11-01T09:00:00"));
        historical.setConfirmedAt(LocalDateTime.parse("2026-10-20T10:00:00"));
        persist(historical);
        assertEquals("2026-08", historical.getAccountingMonth());
    }

    @Test
    void adjustmentLedgerFillsBlankMonthFromBookingAndPreservesExplicitHistoricalMonth() throws Exception {
        for (String missing : Arrays.asList(null, "", "  ")) {
            var ledger = new PointAdjustmentLedger();
            ledger.setAccountingMonth(missing);
            ledger.setCreatedAt(LocalDateTime.parse("2026-10-20T10:00:00"));
            persist(ledger);
            assertEquals("2026-10", ledger.getAccountingMonth());
        }
        var historical = new PointAdjustmentLedger();
        historical.setAccountingMonth("2026-07");
        historical.setCreatedAt(LocalDateTime.parse("2026-10-20T10:00:00"));
        persist(historical);
        assertEquals("2026-07", historical.getAccountingMonth());
    }

    @Test
    void newlyPersistedRowsAlwaysReceiveBookingTimeAndAnAccountingMonth() throws Exception {
        var taskLedger = new PointLedger();
        var adjustment = new PointAdjustmentLedger();
        String before = YearMonth.now().toString();
        persist(taskLedger);
        persist(adjustment);
        String after = YearMonth.now().toString();
        assertNotNull(taskLedger.getCreatedAt());
        assertNotNull(adjustment.getCreatedAt());
        assertTrue(List.of(before, after).contains(taskLedger.getAccountingMonth()));
        assertTrue(List.of(before, after).contains(adjustment.getAccountingMonth()));
    }

    private SubTask task() {
        var task = new SubTask();
        task.setId(90L);
        task.setName("跨月任务");
        task.setDesignerId("designer-1");
        task.setAssigneeRole("designer");
        task.setStatus("completed");
        task.setPointRuleCode("D30_6");
        task.setBasePointSnapshot(2.5d);
        task.setMilestoneMonth("2026-09");
        task.setActualDate("2026-11-11");
        task.setCompletedAt(LocalDateTime.parse("2026-11-11T12:00:00"));
        return task;
    }

    private SubTaskDeliveryVersion version(long id, SubTask task, String confirmedAt, double points) {
        var version = new SubTaskDeliveryVersion();
        version.setId(id);
        version.setSubTask(task);
        version.setExpectedPoints(points);
        version.setPointRuleCode("D30_6");
        version.setSubmittedById("designer-1");
        version.setSubmittedAt(LocalDateTime.parse("2026-09-30T10:00:00"));
        version.setConfirmedAt(confirmedAt == null ? null : LocalDateTime.parse(confirmedAt));
        return version;
    }

    private List<PointLedger> saved(int count) {
        var capture = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgers, times(count)).save(capture.capture());
        return capture.getAllValues();
    }

    private static void persist(Object entity) throws Exception {
        var callback = Arrays.stream(entity.getClass().getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PrePersist.class))
                .findFirst()
                .orElseThrow();
        callback.setAccessible(true);
        callback.invoke(entity);
    }
}
