package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.performance.repository.MonthlyPerformanceConfigRepository;
import com.emie.designpm.points.repository.*;
import com.emie.designpm.project.repository.SubTaskRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.generate_statistics=true"
        },
        showSql = false)
@ContextConfiguration(classes = LeaderboardQueryRegressionTest.Persistence.class)
class LeaderboardQueryRegressionTest {
    @Configuration
    @EnableJpaRepositories(basePackageClasses = {PointLedgerRepository.class, UserRepository.class})
    @EntityScan(basePackageClasses = PointLedger.class)
    static class Persistence {}

    @Autowired
    EntityManager em;

    @Autowired
    PointLedgerRepository ledgers;

    @Autowired
    PointAdjustmentLedgerRepository adjustments;

    @Autowired
    UserRepository users;

    private PerformanceService service;

    @BeforeEach
    void prepareAccountingMonths() {
        em.persist(User.builder().userId("d1").name("设计师一").role("designer").build());
        em.persist(User.builder().userId("d2").name("设计师二").role("designer").build());
        ledger("d1", "2026-07", 100, true, 1L);
        ledger("d1", "2026-07", 500, false, 2L);
        ledger("d2", "2026-08", 40, true, 3L);
        ledger("former", "2026-07", 10, true, 4L);
        adjustment("d1", "2026-07", -5, 1L);
        adjustment("d2", "2026-07", 7, 2L);
        adjustment("d1", "2026-08", 20, 3L);
        em.flush();
        em.clear();
        service = new PerformanceService(
                ledgers,
                adjustments,
                users,
                mock(StandardPointConfigRepository.class),
                mock(MonthlyPerformanceConfigRepository.class),
                mock(SystemConfigRepository.class),
                mock(SubTaskRepository.class),
                mock(PointRuleRepository.class));
    }

    @Test
    void boardKeepsAccountingMonthDeductionsRankingAndMissingUsersWithThreeQueries() {
        var statistics =
                em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<Map<String, Object>> july = service.leaderboard("2026-07");
        assertEquals(
                List.of("d1", "former", "d2"),
                july.stream().map(row -> row.get("userId")).toList());
        assertEquals(
                List.of(95d, 10d, 7d),
                july.stream().map(row -> row.get("points")).toList());
        assertEquals("设计师一", july.getFirst().get("name"));
        assertFalse(july.get(1).containsKey("name"));
        assertEquals("设计师二", july.get(2).get("name"));
        assertEquals(3, statistics.getPrepareStatementCount(), "两次积分聚合加一次批量姓名查询");
        assertEquals(2, statistics.getEntityLoadCount(), "不应加载流水实体");
        assertEquals(
                List.of(40d, 20d),
                service.leaderboard("2026-08").stream()
                        .map(row -> row.get("points"))
                        .toList());
    }

    @Test
    void emptyMonthRunsOnlyTwoAggregatesWithoutLoadingHistoricalRowsOrUsers() {
        var statistics =
                em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertTrue(service.leaderboard("2026-12").isEmpty());
        assertEquals(2, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void omittedMonthIncludesAllMonths(String month) {
        assertEquals(
                List.of(115d, 47d, 10d),
                service.leaderboard(month).stream()
                        .map(row -> row.get("points"))
                        .toList());
    }

    private void ledger(String user, String month, double points, boolean performance, Long task) {
        PointLedger ledger = new PointLedger();
        ledger.setUserId(user);
        ledger.setSubTaskId(task);
        ledger.setRuleCode("A1");
        ledger.setAccountingMonth(month);
        ledger.setPoints(points);
        ledger.setCountInPerformance(performance);
        ledger.setCreatedAt(LocalDateTime.of(2026, 8, 5, 10, 0));
        em.persist(ledger);
    }

    private void adjustment(String user, String month, int points, Long source) {
        PointAdjustmentLedger adjustment = new PointAdjustmentLedger();
        adjustment.setUserId(user);
        adjustment.setAccountingMonth(month);
        adjustment.setPoints(points);
        adjustment.setSourceType("MANUAL");
        adjustment.setSourceId(source);
        adjustment.setReason("跨月入账回归");
        adjustment.setCreatedBy("admin");
        adjustment.setCreatedAt(LocalDateTime.of(2026, 8, 5, 10, 0));
        em.persist(adjustment);
    }
}
