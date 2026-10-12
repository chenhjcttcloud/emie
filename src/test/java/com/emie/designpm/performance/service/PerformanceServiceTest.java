package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.performance.repository.MonthlyPerformanceConfigRepository;
import com.emie.designpm.performance.repository.MonthlyUserPointTargetRepository;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.points.repository.StandardPointConfigRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PerformanceServiceTest {
    @Test
    void monthlyDesignerReportUsesAccountingMonthIncludingAdjustmentsAndKeepsCompletionDetails() throws Exception {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        UserRepository users = mock(UserRepository.class);
        SubTaskRepository subTasks = mock(SubTaskRepository.class);
        PointRuleRepository rules = mock(PointRuleRepository.class);
        User designer = User.builder()
                .userId("d1")
                .name("设计师")
                .role("designer")
                .status("active")
                .build();
        User noCompletedTasks = User.builder()
                .userId("d2")
                .name("无完成任务")
                .role("designer")
                .status("active")
                .build();
        User noPoints = User.builder()
                .userId("d3")
                .name("无积分")
                .role("designer")
                .status("active")
                .build();
        when(users.findByRole("designer")).thenReturn(List.of(designer, noCompletedTasks, noPoints));
        Project project = new Project();
        project.setProductName("项目 A");
        project.setType("regular");
        SubTask task = new SubTask();
        task.setId(10L);
        task.setName("包装设计");
        task.setDesignerId("d1");
        task.setPointRuleCode("A1");
        task.setCompletedAt(LocalDateTime.of(2026, 1, 31, 23, 59));
        task.setProject(project);
        when(subTasks.findDesignerTasksCompletedBetween(
                        LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0)))
                .thenReturn(List.of(task));
        PointLedger ledger = new PointLedger();
        ledger.setUserId("d1");
        ledger.setSubTaskId(10L);
        ledger.setPoints(12d);
        ledger.setCountInPerformance(true);
        ledger.setAccountingMonth("2025-12");
        when(ledgers.findBySubTaskIdIn(List.of(10L))).thenReturn(List.of(ledger));
        PointLedger january = new PointLedger();
        january.setUserId("d1");
        january.setPoints(100d);
        january.setCountInPerformance(true);
        january.setAccountingMonth("2026-01");
        january.setCreatedAt(LocalDateTime.of(2026, 2, 5, 10, 0));
        when(ledgers.findAll()).thenReturn(List.of(ledger, january));
        PointAdjustmentLedger deduction = new PointAdjustmentLedger();
        deduction.setUserId("d1");
        deduction.setAccountingMonth("2026-01");
        deduction.setPoints(-5);
        PointAdjustmentLedger bonus = new PointAdjustmentLedger();
        bonus.setUserId("d2");
        bonus.setAccountingMonth("2026-01");
        bonus.setPoints(7);
        when(adjustments.findAll()).thenReturn(List.of(deduction, bonus));
        PointRule rule = new PointRule();
        rule.setRuleCode("A1");
        rule.setCategory("A");
        when(rules.findAll()).thenReturn(List.of(rule));

        PerformanceService service = new PerformanceService(
                ledgers,
                adjustments,
                users,
                mock(StandardPointConfigRepository.class),
                mock(MonthlyPerformanceConfigRepository.class),
                mock(SystemConfigRepository.class),
                subTasks,
                rules);
        Map<String, Object> report = service.designerMonthlyReport("2026-01");
        Map<String, Object> row = ((List<Map<String, Object>>) report.get("designers")).getFirst();

        assertEquals(1, row.get("completedCount"));
        assertEquals(95d, row.get("score"));
        assertEquals(12d, ((Map<?, ?>) ((List<?>) row.get("tasks")).getFirst()).get("score"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.get("designers");
        assertEquals(0, rows.get(1).get("completedCount"));
        assertEquals(7d, rows.get(1).get("score"));
        assertEquals(0d, rows.get(2).get("score"));
        assertEquals(service.leaderboard("2026-01").getFirst().get("points"), row.get("score"));
        assertEquals(1, ((Map<?, ?>) row.get("categoryCounts")).get("A"));
        assertEquals("regular", ((Map<?, ?>) ((List<?>) row.get("tasks")).getFirst()).get("projectType"));
        byte[] workbook = service.designerMonthlyReportExcel("2026-01");
        assertEquals('P', workbook[0]);
        assertEquals('K', workbook[1]);
        try (XSSFWorkbook exported = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook))) {
            assertEquals(
                    "当月归属积分（含调账）", exported.getSheetAt(0).getRow(0).getCell(4).getStringCellValue());
            assertEquals(95d, exported.getSheetAt(0).getRow(1).getCell(4).getNumericCellValue());
            assertEquals(7d, exported.getSheetAt(0).getRow(2).getCell(4).getNumericCellValue());
            assertEquals("任务累计积分", exported.getSheetAt(1).getRow(0).getCell(8).getStringCellValue());
            assertEquals(12d, exported.getSheetAt(1).getRow(1).getCell(8).getNumericCellValue());
        }
        when(subTasks.findDesignerTasksCompletedBetween(any(), any())).thenReturn(List.of());
        List<Map<String, Object>> withoutCompletedTasks = (List<Map<String, Object>>)
                service.designerMonthlyReport("2026-01").get("designers");
        assertEquals(0, withoutCompletedTasks.getFirst().get("completedCount"));
        assertEquals(95d, withoutCompletedTasks.getFirst().get("score"));
    }

    @Test
    void appliesSalesBracketButKeepsTrialSalaryAsSimulation() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        UserRepository users = mock(UserRepository.class);
        StandardPointConfigRepository standards = mock(StandardPointConfigRepository.class);
        MonthlyPerformanceConfigRepository months = mock(MonthlyPerformanceConfigRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        PointLedger ledger = new PointLedger();
        ledger.setUserId("u1");
        ledger.setPoints(110d);
        ledger.setCountInPerformance(true);
        ledger.setAccountingMonth("2026-08");
        ledger.setCreatedAt(LocalDateTime.now());
        when(ledgers.findAll()).thenReturn(List.of(ledger));
        when(adjustments.findAll()).thenReturn(List.of());
        StandardPointConfig standard = new StandardPointConfig();
        standard.setConfigCode("u1");
        standard.setPoints(100);
        standard.setPerformanceBase(1000d);
        standard.setDepartmentType("SUPPORT");
        standard.setEnabled(true);
        when(standards.findByConfigCode("u1")).thenReturn(Optional.of(standard));
        MonthlyPerformanceConfig month = new MonthlyPerformanceConfig();
        month.setMonthKey("2026-08");
        month.setTargetPoints(100);
        month.setSalesAmount(360d);
        month.setMultiplier(1d);
        when(months.findByMonthKey("2026-08")).thenReturn(Optional.of(month));
        when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
        Map<String, Object> preview = new PerformanceService(
                        ledgers,
                        adjustments,
                        users,
                        standards,
                        months,
                        configs,
                        mock(SubTaskRepository.class),
                        mock(PointRuleRepository.class))
                .preview("u1", "2026-08");
        assertEquals(1d, (Double) preview.get("companyCoefficient"), .001);
        assertEquals(1100d, (Double) preview.get("simulatedPerformanceSalary"), .001);
        assertEquals(false, preview.get("officiallyApplied"));
        assertNull(preview.get("payablePerformanceSalary"));
    }

    @Test
    void permanentDesignerTargetOverridesLegacyPersonalStandard() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        UserRepository users = mock(UserRepository.class);
        StandardPointConfigRepository standards = mock(StandardPointConfigRepository.class);
        MonthlyPerformanceConfigRepository months = mock(MonthlyPerformanceConfigRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        MonthlyUserPointTargetRepository targets = mock(MonthlyUserPointTargetRepository.class);
        when(ledgers.findAll()).thenReturn(List.of());
        when(adjustments.findAll()).thenReturn(List.of());
        StandardPointConfig standard = new StandardPointConfig();
        standard.setConfigCode("designer-1");
        standard.setPoints(100);
        standard.setPerformanceBase(0d);
        standard.setDepartmentType("SUPPORT");
        standard.setEnabled(true);
        when(standards.findByConfigCode("designer-1")).thenReturn(Optional.of(standard));
        MonthlyUserPointTarget target = new MonthlyUserPointTarget();
        target.setMonthKey("PERMANENT");
        target.setUserId("designer-1");
        target.setTargetPoints(160);
        when(targets.findByUserId("designer-1")).thenReturn(Optional.of(target));
        when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
        PerformanceService service = new PerformanceService(
                ledgers,
                adjustments,
                users,
                standards,
                months,
                configs,
                mock(SubTaskRepository.class),
                mock(PointRuleRepository.class));
        service.monthlyUserTargets(targets);
        assertEquals(160, service.preview("designer-1", "2026-08").get("targetPoints"));
    }

    @Test
    void designerTargetsExposeFeishuUserIdForDisplay() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        UserRepository users = mock(UserRepository.class);
        StandardPointConfigRepository standards = mock(StandardPointConfigRepository.class);
        MonthlyPerformanceConfigRepository months = mock(MonthlyPerformanceConfigRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        MonthlyUserPointTargetRepository targets = mock(MonthlyUserPointTargetRepository.class);
        User designer = User.builder()
                .userId("designer-1")
                .feishuUserId("feishu-user-1")
                .name("设计师")
                .role("designer")
                .status("active")
                .build();
        when(targets.findAll()).thenReturn(List.of());
        when(users.findByRole("designer")).thenReturn(List.of(designer));
        PerformanceService service = new PerformanceService(
                ledgers,
                adjustments,
                users,
                standards,
                months,
                configs,
                mock(SubTaskRepository.class),
                mock(PointRuleRepository.class));
        service.monthlyUserTargets(targets);

        assertEquals(
                "feishu-user-1", service.designerTargets("2026-09").getFirst().get("feishuUserId"));
    }

    @Test
    void leaderboardAttributesAdjustmentsByAccountingMonthNotCreatedAt() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        UserRepository users = mock(UserRepository.class);
        StandardPointConfigRepository standards = mock(StandardPointConfigRepository.class);
        MonthlyPerformanceConfigRepository months = mock(MonthlyPerformanceConfigRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        // P1-4：PO 履职 7 月进展 8 月确认，调账 accountingMonth=2026-07（createdAt 在 8 月）—— 月度统计按 accounting_month 归 7 月。
        Object[] row = {"u1", 30d};
        when(adjustments.sumPointsByMonth(eq("2026-07"), any(), any())).thenReturn(List.<Object[]>of(row));
        when(ledgers.sumPerformancePointsByMonth(eq("2026-07"), any(), any())).thenReturn(List.of());
        List<Map<String, Object>> board = new PerformanceService(
                        ledgers,
                        adjustments,
                        users,
                        standards,
                        months,
                        configs,
                        mock(SubTaskRepository.class),
                        mock(PointRuleRepository.class))
                .leaderboard("2026-07");
        assertEquals(1, board.size());
        assertEquals("u1", board.get(0).get("userId"));
        assertEquals(30d, ((Number) board.get(0).get("points")).doubleValue(), .001);
    }

    @Test
    void leaderboardFallbackFiltersAdjustmentsByAccountingMonthWhenMonthGiven() {
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        UserRepository users = mock(UserRepository.class);
        StandardPointConfigRepository standards = mock(StandardPointConfigRepository.class);
        MonthlyPerformanceConfigRepository months = mock(MonthlyPerformanceConfigRepository.class);
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        // 聚合查询未命中（旧仓储 mock 兼容分支）：调账按 accounting_month 而非 created_at 归月。
        PointAdjustmentLedger july = new PointAdjustmentLedger();
        july.setUserId("u1");
        july.setPoints(30);
        july.setAccountingMonth("2026-07");
        july.setCreatedAt(LocalDateTime.of(2026, 8, 5, 10, 0));
        PointAdjustmentLedger august = new PointAdjustmentLedger();
        august.setUserId("u1");
        august.setPoints(20);
        august.setAccountingMonth("2026-08");
        august.setCreatedAt(LocalDateTime.of(2026, 8, 6, 10, 0));
        when(adjustments.findAll()).thenReturn(List.of(july, august));
        when(ledgers.findAll()).thenReturn(List.of());
        PerformanceService service = new PerformanceService(
                ledgers,
                adjustments,
                users,
                standards,
                months,
                configs,
                mock(SubTaskRepository.class),
                mock(PointRuleRepository.class));
        double julyPoints = service.leaderboard("2026-07").stream()
                .filter(r -> "u1".equals(r.get("userId")))
                .mapToDouble(r -> ((Number) r.get("points")).doubleValue())
                .sum();
        double augustPoints = service.leaderboard("2026-08").stream()
                .filter(r -> "u1".equals(r.get("userId")))
                .mapToDouble(r -> ((Number) r.get("points")).doubleValue())
                .sum();
        assertEquals(30d, julyPoints, .001);
        assertEquals(20d, augustPoints, .001);
    }
}
