package com.emie.designpm.points.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = PointsBalancePrecisionTest.Persistence.class)
class PointsBalancePrecisionTest {
    @Configuration
    @EnableJpaRepositories(basePackageClasses = PointAdjustmentLedgerRepository.class)
    @EntityScan(basePackageClasses = PointAdjustmentLedger.class)
    static class Persistence {}

    @Autowired
    PointAdjustmentLedgerRepository adjustments;

    @Autowired
    PointLedgerRepository ledgers;

    @Test
    void balancePreservesFractionalCreditsAndDebits() {
        adjustment(1L, 2.5d);
        adjustment(2L, -0.25d);
        assertEquals(2.25d, adjustments.sumPointsByUserId("precision-designer"));
        PointsService service = new PointsService(
                mock(PointRuleRepository.class),
                ledgers,
                mock(ScoringRepository.class),
                adjustments,
                mock(SystemConfigRepository.class));
        assertEquals(2.25d, service.balance("precision-designer"));
    }

    private void adjustment(long sourceId, double points) {
        PointAdjustmentLedger row = new PointAdjustmentLedger();
        row.setUserId("precision-designer");
        row.setSourceType("MANUAL");
        row.setSourceId(sourceId);
        row.setPoints(points);
        row.setReason("小数余额回归");
        row.setCreatedBy("test-admin");
        adjustments.saveAndFlush(row);
    }
}
