package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.User;
import com.emie.designpm.performance.repository.MonthlyPerformanceConfigRepository;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.points.repository.StandardPointConfigRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesignerLeaderboardServiceTest {
    private final PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
    private final PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PerformanceService service = new PerformanceService(
            ledgers,
            adjustments,
            users,
            mock(StandardPointConfigRepository.class),
            mock(MonthlyPerformanceConfigRepository.class),
            mock(SystemConfigRepository.class),
            mock(SubTaskRepository.class),
            mock(PointRuleRepository.class));

    @Test
    void monthlyRosterIncludesAllActiveDesignersButExcludesInactiveNonDesignerAndOrphanAccounts() {
        var first = user("a-designer", "甲设计师", "designer", "active");
        first.setPassword("private-password-fixture");
        first.setPhone("private-phone-fixture");
        first.setFeishuUserId("private-feishu-fixture");
        when(users.findByRole("designer"))
                .thenReturn(List.of(
                        user("z-designer", "乙设计师", "DESIGNER", null),
                        user("zero", "零积分设计师", "designer", "ACTIVE"),
                        user("debit", "扣分设计师", "designer", "active"),
                        user("disabled", "已停用设计师", "designer", "disabled"),
                        user("blank-status", "空状态设计师", "designer", ""),
                        user("planner", "企划", "planner", "active"),
                        user("no-name", null, "designer", "active"),
                        user("blank-name", "  ", "designer", "active"),
                        user("", "空账号", "designer", "active"),
                        user(null, "缺失账号", "designer", "active"),
                        first));
        when(users.findByRole("设计师")).thenReturn(List.of(user("alias", "中文角色设计师", "设计师", "active")));
        when(ledgers.sumPerformancePointsByMonth(eq("2026-10"), any(), any()))
                .thenReturn(rows(
                        row("a-designer", 4.25),
                        row("z-designer", 4),
                        row("planner", 999),
                        row("disabled", 888),
                        row("orphan", 777),
                        row("no-name", 666)));
        when(adjustments.sumPointsByMonth(eq("2026-10"), any(), any()))
                .thenReturn(rows(row("a-designer", -0.25), row("debit", -0.5), row("alias", 1.25)));

        var result = service.designerLeaderboard("2026-10");

        assertEquals(
                List.of("no-name", "a-designer", "z-designer", "alias", "blank-name", "zero", "debit"), ids(result));
        assertEquals(
                List.of(666d, 4d, 4d, 1.25d, 0d, 0d, -0.5d),
                result.stream().map(item -> item.get("points")).toList());
        assertEquals("甲设计师", result.get(1).get("name"));
        assertEquals("no-name", result.getFirst().get("name"));
        assertEquals("blank-name", result.get(4).get("name"));
        assertTrue(result.stream().allMatch(item -> item.keySet().equals(Set.of("userId", "name", "points"))));
        verify(ledgers)
                .sumPerformancePointsByMonth(
                        "2026-10", LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0));
        verify(adjustments)
                .sumPointsByMonth("2026-10", LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0));
        verify(ledgers, never()).findAll();
        verify(adjustments, never()).findAll();
        verify(users, never()).findAll();
    }

    @Test
    void emptyMonthsIncludeAllActiveDesignersWithStableTiesAndNoDuplicateAliasRows() {
        var a = user("a", "同名设计师", "designer", "active");
        var z = user("z", "同名设计师", "designer", "active");
        var alias = user("m", "中文角色", "设计师", "active");
        when(users.findByRole("designer")).thenReturn(List.of(z, a)).thenReturn(List.of(a, z));
        when(users.findByRole("设计师")).thenReturn(List.of(alias, a));

        var first = service.designerLeaderboard("2026-12");
        var second = service.designerLeaderboard("2026-12");

        assertEquals(List.of("a", "m", "z"), ids(first));
        assertEquals(first, second);
        assertTrue(first.stream().allMatch(item -> Double.valueOf(0d).equals(item.get("points"))));
        verify(users, never()).findByUserIdIn(anyCollection());
    }

    @Test
    void cumulativeQueriesReuseTheExistingAllTimeAggregatesAndBlankMonthMeansCumulative() {
        when(users.findByRole("designer")).thenReturn(List.of(user("d1", "设计师", "designer", "active")));
        when(ledgers.sumPerformancePointsByMonth(isNull(), isNull(), isNull())).thenReturn(rows(row("d1", 120.75)));
        when(adjustments.sumPointsByMonth(isNull(), isNull(), isNull())).thenReturn(rows(row("d1", -10.25)));

        assertEquals(110.5d, service.designerLeaderboard(null).getFirst().get("points"));
        assertEquals(110.5d, service.designerLeaderboard("").getFirst().get("points"));
        assertEquals(110.5d, service.designerLeaderboard("  ").getFirst().get("points"));
        verify(ledgers, times(3)).sumPerformancePointsByMonth(null, null, null);
        verify(adjustments, times(3)).sumPointsByMonth(null, null, null);
        verify(ledgers, never()).findAll();
        verify(adjustments, never()).findAll();
    }

    @Test
    void invalidMonthIsRejectedBeforeReadingUsersOrPointData() {
        for (String month :
                List.of("2026", "0000-10", "2026-00", "2026-13", "2026-1", "2026-10-01", "2026-10' OR 1=1")) {
            assertThrows(IllegalArgumentException.class, () -> service.designerLeaderboard(month), month);
        }
        verifyNoInteractions(ledgers, adjustments, users);
    }

    @Test
    void originalLeaderboardStillIncludesScoredNonDesignersAndDoesNotAddZeroRows() {
        when(users.findByRole("designer"))
                .thenReturn(
                        List.of(user("d1", "设计师", "designer", "active"), user("zero", "零分设计师", "designer", "active")));
        when(ledgers.sumPerformancePointsByMonth(eq("2026-10"), any(), any()))
                .thenReturn(rows(row("d1", 2), row("planner", 8)));

        assertEquals(List.of("planner", "d1"), ids(service.leaderboard("2026-10")));
        assertEquals(List.of("d1", "zero"), ids(service.designerLeaderboard("2026-10")));
    }

    @Test
    void noEligibleRosterDoesNotExposeOrphanScores() {
        when(ledgers.sumPerformancePointsByMonth(eq("2026-10"), any(), any()))
                .thenReturn(rows(row("missing-account", 1000)));

        assertTrue(service.designerLeaderboard("2026-10").isEmpty());
        verify(users).findByRole("designer");
        verify(users).findByRole("设计师");
        verify(users, never()).findAll();
    }

    private User user(String id, String name, String role, String status) {
        return User.builder().userId(id).name(name).role(role).status(status).build();
    }

    private static Object[] row(String id, double points) {
        return new Object[] {id, points};
    }

    private static List<Object[]> rows(Object[]... values) {
        return new ArrayList<>(List.of(values));
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(item -> (String) item.get("userId")).toList();
    }
}
