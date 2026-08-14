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

 @Test void monthlyDesignerTargetOverridesLegacyPersonalStandard(){
  PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);UserRepository users=mock(UserRepository.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);SystemConfigRepository configs=mock(SystemConfigRepository.class);MonthlyUserPointTargetRepository targets=mock(MonthlyUserPointTargetRepository.class);
  when(ledgers.findAll()).thenReturn(List.of());when(adjustments.findAll()).thenReturn(List.of());
  StandardPointConfig standard=new StandardPointConfig();standard.setConfigCode("designer-1");standard.setPoints(100);standard.setPerformanceBase(0d);standard.setDepartmentType("SUPPORT");standard.setEnabled(true);when(standards.findByConfigCode("designer-1")).thenReturn(Optional.of(standard));
  MonthlyUserPointTarget target=new MonthlyUserPointTarget();target.setMonthKey("2026-08");target.setUserId("designer-1");target.setTargetPoints(160);when(targets.findByMonthKeyAndUserId("2026-08","designer-1")).thenReturn(Optional.of(target));when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
  PerformanceService service=new PerformanceService(ledgers,adjustments,users,standards,months,configs);service.monthlyUserTargets(targets);
  assertEquals(160,service.preview("designer-1","2026-08").get("targetPoints"));
 }
}
