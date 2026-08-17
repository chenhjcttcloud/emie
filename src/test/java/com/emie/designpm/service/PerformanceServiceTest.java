package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerformanceServiceTest {
 @Test void appliesSalesBracketButKeepsTrialSalaryAsSimulation(){
  PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);UserRepository users=mock(UserRepository.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);SystemConfigRepository configs=mock(SystemConfigRepository.class);
  PointLedger ledger=new PointLedger();ledger.setUserId("u1");ledger.setPoints(110d);ledger.setCountInPerformance(true);ledger.setAccountingMonth("2026-08");ledger.setCreatedAt(LocalDateTime.now());when(ledgers.findAll()).thenReturn(List.of(ledger));when(adjustments.findAll()).thenReturn(List.of());
  StandardPointConfig standard=new StandardPointConfig();standard.setConfigCode("u1");standard.setPoints(100);standard.setPerformanceBase(1000d);standard.setDepartmentType("SUPPORT");standard.setEnabled(true);when(standards.findByConfigCode("u1")).thenReturn(Optional.of(standard));
  MonthlyPerformanceConfig month=new MonthlyPerformanceConfig();month.setMonthKey("2026-08");month.setTargetPoints(100);month.setSalesAmount(360d);month.setMultiplier(1d);when(months.findByMonthKey("2026-08")).thenReturn(Optional.of(month));when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
  Map<String,Object> preview=new PerformanceService(ledgers,adjustments,users,standards,months,configs).preview("u1","2026-08");
  assertEquals(1d,(Double)preview.get("companyCoefficient"),.001);assertEquals(1100d,(Double)preview.get("simulatedPerformanceSalary"),.001);assertEquals(false,preview.get("officiallyApplied"));assertNull(preview.get("payablePerformanceSalary"));
 }

 @Test void permanentDesignerTargetOverridesLegacyPersonalStandard(){
  PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);UserRepository users=mock(UserRepository.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);SystemConfigRepository configs=mock(SystemConfigRepository.class);MonthlyUserPointTargetRepository targets=mock(MonthlyUserPointTargetRepository.class);
  when(ledgers.findAll()).thenReturn(List.of());when(adjustments.findAll()).thenReturn(List.of());
  StandardPointConfig standard=new StandardPointConfig();standard.setConfigCode("designer-1");standard.setPoints(100);standard.setPerformanceBase(0d);standard.setDepartmentType("SUPPORT");standard.setEnabled(true);when(standards.findByConfigCode("designer-1")).thenReturn(Optional.of(standard));
  MonthlyUserPointTarget target=new MonthlyUserPointTarget();target.setMonthKey("PERMANENT");target.setUserId("designer-1");target.setTargetPoints(160);when(targets.findByUserId("designer-1")).thenReturn(Optional.of(target));when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
  PerformanceService service=new PerformanceService(ledgers,adjustments,users,standards,months,configs);service.monthlyUserTargets(targets);
  assertEquals(160,service.preview("designer-1","2026-08").get("targetPoints"));
 }

 @Test void leaderboardAttributesAdjustmentsByAccountingMonthNotCreatedAt(){
  PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);UserRepository users=mock(UserRepository.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);SystemConfigRepository configs=mock(SystemConfigRepository.class);
  // P1-4：PO 履职 7 月进展 8 月确认，调账 accountingMonth=2026-07（createdAt 在 8 月）—— 月度统计按 accounting_month 归 7 月。
  Object[] row={ "u1", 30d };
  when(adjustments.sumPointsByMonth(eq("2026-07"), any(), any())).thenReturn(List.<Object[]>of(row));
  when(ledgers.sumPerformancePointsByMonth(eq("2026-07"), any(), any())).thenReturn(List.of());
  List<Map<String,Object>> board=new PerformanceService(ledgers,adjustments,users,standards,months,configs).leaderboard("2026-07");
  assertEquals(1,board.size());
  assertEquals("u1",board.get(0).get("userId"));
  assertEquals(30d,((Number)board.get(0).get("points")).doubleValue(),.001);
 }

 @Test void leaderboardFallbackFiltersAdjustmentsByAccountingMonthWhenMonthGiven(){
  PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);UserRepository users=mock(UserRepository.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);SystemConfigRepository configs=mock(SystemConfigRepository.class);
  // 聚合查询未命中（旧仓储 mock 兼容分支）：调账按 accounting_month 而非 created_at 归月。
  PointAdjustmentLedger july=new PointAdjustmentLedger();july.setUserId("u1");july.setPoints(30);july.setAccountingMonth("2026-07");july.setCreatedAt(LocalDateTime.of(2026,8,5,10,0));
  PointAdjustmentLedger august=new PointAdjustmentLedger();august.setUserId("u1");august.setPoints(20);august.setAccountingMonth("2026-08");august.setCreatedAt(LocalDateTime.of(2026,8,6,10,0));
  when(adjustments.findAll()).thenReturn(List.of(july,august));when(ledgers.findAll()).thenReturn(List.of());
  PerformanceService service=new PerformanceService(ledgers,adjustments,users,standards,months,configs);
  double julyPoints=service.leaderboard("2026-07").stream().filter(r->"u1".equals(r.get("userId"))).mapToDouble(r->((Number)r.get("points")).doubleValue()).sum();
  double augustPoints=service.leaderboard("2026-08").stream().filter(r->"u1".equals(r.get("userId"))).mapToDouble(r->((Number)r.get("points")).doubleValue()).sum();
  assertEquals(30d,julyPoints,.001);
  assertEquals(20d,augustPoints,.001);
 }
}
