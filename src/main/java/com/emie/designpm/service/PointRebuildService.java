package com.emie.designpm.service;

import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.repository.PointLedgerRepository;
import com.emie.designpm.repository.PoPointLedgerRepository;
import com.emie.designpm.repository.SubTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;

@Service
public class PointRebuildService {
    private final PointLedgerRepository ledgers;
    private final PointAdjustmentLedgerRepository adjustments;
    private final PoPointLedgerRepository poLedgers;
    private final SubTaskRepository tasks;
    private final PointsService points;

    public PointRebuildService(PointLedgerRepository ledgers, PointAdjustmentLedgerRepository adjustments,
                               PoPointLedgerRepository poLedgers, SubTaskRepository tasks, PointsService points) {
        this.ledgers = ledgers; this.adjustments = adjustments; this.poLedgers = poLedgers;
        this.tasks = tasks; this.points = points;
    }

    @Transactional
    public Result rebuild() {
        long oldLedgerCount = ledgers.count();
        long oldPoCount = poLedgers.count();
        int oldAdjustmentCount = adjustments.deleteBySourceTypes(Set.of("MANUAL", "MANUAL_ADJUSTMENT", "APPEAL", "PO_PROGRESS"));
        poLedgers.deleteAllInBatch();
        ledgers.deleteAllInBatch();
        int rebuilt = 0;
        for (SubTask task : tasks.findAll()) {
            if (!Set.of("planner_approved", "sales_approved", "admin_approved", "completed").contains(task.getStatus())) continue;
            points.awardBaseSubmission(task);
            if ("completed".equals(task.getStatus())) points.awardQualityCompletion(task);
            rebuilt++;
        }
        return new Result(oldLedgerCount, oldAdjustmentCount, oldPoCount, rebuilt, ledgers.count());
    }

    public record Result(long removedTaskLedgers, int removedAdjustments, long removedPoLedgers,
                         int tasksRebuilt, long rebuiltTaskLedgers) {}
}
