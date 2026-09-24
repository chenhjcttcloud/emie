package com.emie.designpm.designrequirement.service;

import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.service.ConceptPointCapService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import org.springframework.stereotype.Service;

@Service
public class DesignRequirementPointsService {
    private final PointAdjustmentLedgerRepository ledgers;
    private final ConceptPointCapService conceptCap;

    public DesignRequirementPointsService(PointAdjustmentLedgerRepository ledgers, ConceptPointCapService conceptCap) {
        this.ledgers = ledgers;
        this.conceptCap = conceptCap;
    }

    public void awardOnDelivery(DesignRequirement requirement, String createdBy) {
        if (requirement.isPointsAwarded()
                || ledgers.findBySourceTypeAndSourceId("DESIGN_REQUIREMENT", requirement.getId())
                        .isPresent()) return;
        double points = requirement.getBasePointSnapshot() == null
                ? 0d
                : requirement.getBasePointSnapshot() * requirement.getDifficultyMultiplierSnapshot();
        if (ConceptPointCapService.cappedRule(requirement.getPointRuleCode())
                && !requirement.isConceptReserveExempt()) {
            points = Math.min(
                    points,
                    conceptCap.remaining(
                            requirement.getDesignerId(), YearMonth.now().toString()));
        }
        points = BigDecimal.valueOf(points).setScale(2, RoundingMode.HALF_UP).doubleValue();
        if (points > 0d) {
            PointAdjustmentLedger award = new PointAdjustmentLedger();
            award.setUserId(requirement.getDesignerId());
            award.setSourceType("DESIGN_REQUIREMENT");
            award.setSourceId(requirement.getId());
            award.setPoints(points);
            award.setReason("设计/送审需求交付：" + requirement.getName() + "（" + requirement.getPointRuleCode() + "）");
            award.setCreatedBy(createdBy);
            ledgers.save(award);
        }
        requirement.setPointsAwarded(true);
    }
}
