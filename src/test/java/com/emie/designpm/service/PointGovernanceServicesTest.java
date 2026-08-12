package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
  assertEquals("APPROVED",service.adminReview(2L,"APPROVE","补记漏算积分",-5,session("admin-1","admin")).getStatus());verify(adjustments).save(argThat(x->x.getPoints()==-5&&"APPEAL".equals(x.getSourceType())));
 }

 @Test void poConfirmationCreatesExactlyOneLedgerAndOnlyOwnerCanSubmit(){
  PoPointProjectRepository projects=mock(PoPointProjectRepository.class);PoMonthlyProgressRepository progress=mock(PoMonthlyProgressRepository.class);PoPointLedgerRepository ledgers=mock(PoPointLedgerRepository.class);PointAdjustmentLedgerRepository adjustments=mock(PointAdjustmentLedgerRepository.class);PoPointsService service=new PoPointsService(projects,progress,ledgers,adjustments);
  PoPointProject project=new PoPointProject();project.setId(3L);project.setOwnerUserId("owner");project.setOwnerName("负责人");project.setEnabled(true);project.setMonthlyPoints(30);when(projects.findById(3L)).thenReturn(Optional.of(project));when(progress.findByPoProjectIdAndMonthKey(3L,"2026-08")).thenReturn(Optional.empty());when(progress.save(any())).thenAnswer(i->{PoMonthlyProgress g=i.getArgument(0);if(g.getId()==null)g.setId(9L);return g;});
  assertThrows(SecurityException.class,()->service.submit(3L,"2026-08","进展",session("other","designer")));
  PoMonthlyProgress submitted=service.submit(3L,"2026-08","完成评审",session("owner","designer"));when(progress.findLockedById(9L)).thenReturn(Optional.of(submitted));when(ledgers.findByProgressId(9L)).thenReturn(Optional.empty(),Optional.of(new PoPointLedger()));when(adjustments.findBySourceTypeAndSourceId("PO_PROGRESS",9L)).thenReturn(Optional.empty());
  assertEquals("CONFIRMED",service.review(9L,true,"通过",session("admin","admin")).getStatus());verify(ledgers,times(1)).save(any(PoPointLedger.class));verify(adjustments,times(1)).save(any(PointAdjustmentLedger.class));
  assertEquals("CONFIRMED",service.review(9L,true,"重复确认",session("admin","admin")).getStatus());verify(ledgers,times(1)).save(any(PoPointLedger.class));
 }

 @Test void archiveProtectionIsDerivedAndArchivedRecordIsImmutable(){
  MonthlyPointArchiveRepository repo=mock(MonthlyPointArchiveRepository.class);MonthlyPointArchiveService service=new MonthlyPointArchiveService(repo);when(repo.findByMonthKeyAndUserId("2026-08","u1")).thenReturn(Optional.empty());when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  MonthlyPointArchive a=service.save("2026-08","u1",80,100,60,true,75d,session("admin","admin"));assertTrue(a.getInsufficientSupplyProtection());assertEquals(75d,a.getQuarterlyAveragePoints());
  a.setStatus("ARCHIVED");when(repo.findByMonthKeyAndUserId("2026-08","u1")).thenReturn(Optional.of(a));assertThrows(IllegalStateException.class,()->service.save("2026-08","u1",90,100,60,true,80d,session("admin","admin")));
  assertEquals(List.of(),service.list(null,session("u1","designer")));verify(repo).findByUserIdOrderByMonthKeyDesc("u1");
 }
 private AuthController.AuthSession session(String id,String role){return new AuthController.AuthSession(id,role,id);}
}
