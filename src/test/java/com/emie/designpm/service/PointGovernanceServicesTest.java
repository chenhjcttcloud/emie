package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PointGovernanceServicesTest {
 @Test void appealEnforcesOwnershipAndReviewSequence(){
  PointAppealRepository appeals=mock(PointAppealRepository.class);PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);PointAppealService service=new PointAppealService(appeals,ledgers,adjustments);
  PointLedger ledger=new PointLedger();ledger.setId(7L);ledger.setUserId("designer-1");when(ledgers.findById(7L)).thenReturn(Optional.of(ledger));when(appeals.save(any())).thenAnswer(i->i.getArgument(0));
  assertThrows(SecurityException.class,()->service.submit(7L,"OTHER","wrong",session("designer-2","designer")));
  PointAppeal submitted=service.submit(7L,"base_points","分值不正确",session("designer-1","designer"));assertEquals("SUBMITTED",submitted.getStatus());assertEquals("BASE_POINTS",submitted.getType());
  submitted.setId(1L);when(appeals.findById(1L)).thenReturn(Optional.of(submitted));
  assertThrows(SecurityException.class,()->service.plannerProcess(1L,"APPROVE","同意",session("designer-1","designer")));
  PointAppeal processed=service.plannerProcess(1L,"APPROVE","规则归类有误",session("planner-1","planner"));assertEquals("PLANNER_PROCESSED",processed.getStatus());assertNotNull(processed.getPlannerProcessedAt());
  PointAppeal reviewed=service.adminReview(1L,"REJECT","维持原积分",null,session("admin-1","admin"));assertEquals("REJECTED",reviewed.getStatus());assertNotNull(reviewed.getAdminReviewedAt());
  PointAppeal approved=new PointAppeal();approved.setId(2L);approved.setApplicantUserId("designer-1");approved.setStatus("PLANNER_PROCESSED");when(appeals.findById(2L)).thenReturn(Optional.of(approved));when(adjustments.findBySourceTypeAndSourceId("APPEAL",2L)).thenReturn(Optional.empty());
  IllegalArgumentException negativeScore=assertThrows(IllegalArgumentException.class,()->service.adminReview(2L,"APPROVE","补记漏算积分",-5,session("admin-1","admin")));assertEquals("更正后的分数不能为负数",negativeScore.getMessage());verify(adjustments,never()).save(any());
 }

 @Test void approvedAppealBlocksResubmissionForSameLedger(){
  PointAppealRepository appeals=mock(PointAppealRepository.class);PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);PointAppealService service=new PointAppealService(appeals,ledgers,adjustments);
  PointLedger ledger=new PointLedger();ledger.setId(7L);ledger.setUserId("designer-1");when(ledgers.findById(7L)).thenReturn(Optional.of(ledger));
  when(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatusIn(eq(7L),eq("designer-1"),anyList())).thenReturn(false);
  when(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatus(eq(7L),eq("designer-1"),eq("APPROVED"))).thenReturn(true);
  IllegalStateException e=assertThrows(IllegalStateException.class,()->service.submit(7L,"OTHER","重复申诉",session("designer-1","designer")));
  assertEquals("该积分记录已完成异议终审，不可重复申诉",e.getMessage());
  verify(appeals,never()).save(any());
 }

 @Test void rejectedAppealAllowsResubmissionForSameLedger(){
  PointAppealRepository appeals=mock(PointAppealRepository.class);PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);PointAppealService service=new PointAppealService(appeals,ledgers,adjustments);
  PointLedger ledger=new PointLedger();ledger.setId(7L);ledger.setUserId("designer-1");when(ledgers.findById(7L)).thenReturn(Optional.of(ledger));
  when(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatusIn(eq(7L),eq("designer-1"),anyList())).thenReturn(false);
  when(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatus(eq(7L),eq("designer-1"),eq("APPROVED"))).thenReturn(false);
  when(appeals.save(any())).thenAnswer(i->i.getArgument(0));
  PointAppeal resubmitted=service.submit(7L,"OTHER","驳回后复议",session("designer-1","designer"));
  assertEquals("SUBMITTED",resubmitted.getStatus());
  verify(appeals).save(argThat(x->x.getReason().equals("驳回后复议")));
 }

 @Test void concurrentDuplicateSubmitIsRejectedByUniqueConstraintBackstop(){
  PointAppealRepository appeals=mock(PointAppealRepository.class);PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);PointAppealService service=new PointAppealService(appeals,ledgers,adjustments);
  PointLedger ledger=new PointLedger();ledger.setId(7L);ledger.setUserId("designer-1");when(ledgers.findById(7L)).thenReturn(Optional.of(ledger));
  // 服务层查重与终审查重在并发窗口内都未命中，命中数据库部分唯一索引（V38）兜底。
  when(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatusIn(eq(7L),eq("designer-1"),anyList())).thenReturn(false);
  when(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatus(eq(7L),eq("designer-1"),eq("APPROVED"))).thenReturn(false);
  when(appeals.save(any())).thenThrow(new DataIntegrityViolationException("uk_point_appeal_active_ledger_user"));
  IllegalStateException e=assertThrows(IllegalStateException.class,()->service.submit(7L,"OTHER","并发提交",session("designer-1","designer")));
  assertEquals("该积分记录已有处理中异议，请勿重复提交",e.getMessage());
 }

 @Test void secondApprovalForSameLedgerIsRejectedByApprovedUniqueKey(){
  PointAppealRepository appeals=mock(PointAppealRepository.class);PointLedgerRepository ledgers=mock(PointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);PointAppealService service=new PointAppealService(appeals,ledgers,adjustments);
  PointAppeal pending=new PointAppeal();pending.setId(5L);pending.setApplicantUserId("designer-1");pending.setStatus("PLANNER_PROCESSED");when(appeals.findById(5L)).thenReturn(Optional.of(pending));
  // 同 (ledger,user) 已有一笔 APPROVED：V40 approved_ledger_user_key 唯一索引在保存 APPROVED 时冲突
  when(appeals.save(any())).thenThrow(new DataIntegrityViolationException("uk_point_appeal_approved_ledger_user"));
  IllegalStateException e=assertThrows(IllegalStateException.class,()->service.adminReview(5L,"APPROVE","补记",10,session("admin-1","admin")));
  assertEquals("该积分记录已完成异议终审，不可重复申诉",e.getMessage());
  verify(adjustments,never()).save(any());
 }

 @Test void poConfirmationCreatesExactlyOneLedgerAndOnlyOwnerCanSubmit(){  PoPointProjectRepository projects=mock(PoPointProjectRepository.class);PoMonthlyProgressRepository progress=mock(PoMonthlyProgressRepository.class);PoPointLedgerRepository ledgers=mock(PoPointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);PoPointsService service=new PoPointsService(projects,progress,ledgers,adjustments);
  PoPointProject project=new PoPointProject();project.setId(3L);project.setOwnerUserId("owner");project.setOwnerName("负责人");project.setEnabled(true);project.setMonthlyPoints(30);when(projects.findById(3L)).thenReturn(Optional.of(project));when(progress.findByPoProjectIdAndMonthKey(3L,"2026-08")).thenReturn(Optional.empty());when(progress.save(any())).thenAnswer(i->{PoMonthlyProgress g=i.getArgument(0);if(g.getId()==null)g.setId(9L);return g;});
  assertThrows(SecurityException.class,()->service.submit(3L,"2026-08","进展",session("other","designer")));
  PoMonthlyProgress submitted=service.submit(3L,"2026-08","完成评审",session("owner","designer"));when(progress.findLockedById(9L)).thenReturn(Optional.of(submitted));when(ledgers.findByProgressId(9L)).thenReturn(Optional.empty(),Optional.of(new PoPointLedger()));when(adjustments.findBySourceTypeAndSourceId("PO_PROGRESS",9L)).thenReturn(Optional.empty());
  assertEquals("CONFIRMED",service.review(9L,true,"通过",session("admin","admin")).getStatus());verify(ledgers,times(1)).save(any(PoPointLedger.class));verify(adjustments,times(1)).save(argThat(x->x.getPoints()==30&&"PO_PROGRESS".equals(x.getSourceType())&&"2026-08".equals(x.getAccountingMonth())));
  assertEquals("CONFIRMED",service.review(9L,true,"重复确认",session("admin","admin")).getStatus());verify(ledgers,times(1)).save(any(PoPointLedger.class));
 }

 @Test void archiveProtectionIsDerivedAndArchivedRecordIsImmutable(){
  MonthlyPointArchiveRepository repo=mock(MonthlyPointArchiveRepository.class);MonthlyPointArchiveService service=new MonthlyPointArchiveService(repo);when(repo.findByMonthKeyAndUserId("2026-08","u1")).thenReturn(Optional.empty());when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  MonthlyPointArchive a=service.save("2026-08","u1",80,100,60,true,75d,session("admin","admin"));assertTrue(a.getInsufficientSupplyProtection());assertEquals(75d,a.getQuarterlyAveragePoints());
  a.setStatus("ARCHIVED");when(repo.findByMonthKeyAndUserId("2026-08","u1")).thenReturn(Optional.of(a));assertThrows(IllegalStateException.class,()->service.save("2026-08","u1",90,100,60,true,80d,session("admin","admin")));
  assertEquals(List.of(),service.list(null,session("u1","designer")));verify(repo).findByUserIdOrderByMonthKeyDesc("u1");
 }

 @Test void prepareWithSupplyShortageAppliesProtectionAndQuarterlyAverage(){
  MonthlyPointArchiveRepository repo=mock(MonthlyPointArchiveRepository.class);MonthlyPointArchiveService service=new MonthlyPointArchiveService(repo);PerformanceService performance=mock(PerformanceService.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);service.dependencies(performance,standards,months);
  MonthlyPerformanceConfig config=new MonthlyPerformanceConfig();config.setMonthKey("2026-08");config.setSupplyShortage(true);when(months.findByMonthKey("2026-08")).thenReturn(Optional.of(config));
  Map<String,Object> row=new HashMap<>();row.put("userId","u1");row.put("points",40);when(performance.leaderboard("2026-08")).thenReturn(List.of(row));
  StandardPointConfig standard=new StandardPointConfig();standard.setConfigCode("u1");standard.setPoints(100);standard.setEnabled(true);when(standards.findAll()).thenReturn(List.of(standard));
  MonthlyPointArchive prev1=archive("2026-07",50,100,true);MonthlyPointArchive prev2=archive("2026-06",30,100,true);when(repo.findByMonthKeyAndUserId("2026-07","u1")).thenReturn(Optional.of(prev1));when(repo.findByMonthKeyAndUserId("2026-06","u1")).thenReturn(Optional.of(prev2));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  List<MonthlyPointArchive> result=service.prepare("2026-08",session("admin","admin"));
  MonthlyPointArchive archived=result.get(0);assertTrue(archived.getInsufficientSupplyProtection());assertEquals(0,archived.getSuppliedPoints());assertEquals(40,archived.getEarnedPoints());assertEquals(100,archived.getTargetPoints());assertEquals(40d,archived.getQuarterlyAveragePoints(),.001);
 }

 @Test void prepareWithoutSupplyShortageKeepsTargetAndNoProtection(){
  MonthlyPointArchiveRepository repo=mock(MonthlyPointArchiveRepository.class);MonthlyPointArchiveService service=new MonthlyPointArchiveService(repo);PerformanceService performance=mock(PerformanceService.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);service.dependencies(performance,standards,months);
  MonthlyPerformanceConfig config=new MonthlyPerformanceConfig();config.setMonthKey("2026-08");config.setSupplyShortage(false);when(months.findByMonthKey("2026-08")).thenReturn(Optional.of(config));
  Map<String,Object> row=new HashMap<>();row.put("userId","u1");row.put("points",90);when(performance.leaderboard("2026-08")).thenReturn(List.of(row));
  StandardPointConfig standard=new StandardPointConfig();standard.setConfigCode("u1");standard.setPoints(100);standard.setEnabled(true);when(standards.findAll()).thenReturn(List.of(standard));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  List<MonthlyPointArchive> result=service.prepare("2026-08",session("admin","admin"));
  MonthlyPointArchive archived=result.get(0);assertFalse(archived.getInsufficientSupplyProtection());assertEquals(100,archived.getSuppliedPoints());assertEquals(90d,archived.getQuarterlyAveragePoints(),.001);
 }

 @Test void prepareWithSupplyShortageButHighAttainmentSkipsQuarterlyAverage(){
  MonthlyPointArchiveRepository repo=mock(MonthlyPointArchiveRepository.class);MonthlyPointArchiveService service=new MonthlyPointArchiveService(repo);PerformanceService performance=mock(PerformanceService.class);StandardPointConfigRepository standards=mock(StandardPointConfigRepository.class);MonthlyPerformanceConfigRepository months=mock(MonthlyPerformanceConfigRepository.class);service.dependencies(performance,standards,months);
  MonthlyPerformanceConfig config=new MonthlyPerformanceConfig();config.setMonthKey("2026-08");config.setSupplyShortage(true);when(months.findByMonthKey("2026-08")).thenReturn(Optional.of(config));
  Map<String,Object> row=new HashMap<>();row.put("userId","u1");row.put("points",80);when(performance.leaderboard("2026-08")).thenReturn(List.of(row));
  StandardPointConfig standard=new StandardPointConfig();standard.setConfigCode("u1");standard.setPoints(100);standard.setEnabled(true);when(standards.findAll()).thenReturn(List.of(standard));
  MonthlyPointArchive prev1=archive("2026-07",50,100,true);MonthlyPointArchive prev2=archive("2026-06",30,100,true);when(repo.findByMonthKeyAndUserId("2026-07","u1")).thenReturn(Optional.of(prev1));when(repo.findByMonthKeyAndUserId("2026-06","u1")).thenReturn(Optional.of(prev2));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  List<MonthlyPointArchive> result=service.prepare("2026-08",session("admin","admin"));
  MonthlyPointArchive archived=result.get(0);assertTrue(archived.getInsufficientSupplyProtection());assertEquals(80d,archived.getQuarterlyAveragePoints(),.001);
 }

 private MonthlyPointArchive archive(String month,int earned,int target,boolean protection){
  MonthlyPointArchive a=new MonthlyPointArchive();a.setMonthKey(month);a.setEarnedPoints(earned);a.setTargetPoints(target);a.setInsufficientSupplyProtection(protection);return a;
 }
 private AuthController.AuthSession session(String id,String role){return new AuthController.AuthSession(id,role,id);}
}
