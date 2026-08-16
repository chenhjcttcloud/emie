package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.*;

@Service
public class PointAppealService {
 private static final Set<String> TYPES=Set.of("CATEGORY","BASE_POINTS","DIFFICULTY","QUALITY_BONUS","ELIGIBILITY","OTHER");
 private final PointAppealRepository appeals; private final PointLedgerRepository ledgers; private final PointAdjustmentLedgerRepository adjustments;
 public PointAppealService(PointAppealRepository a,PointLedgerRepository l,PointAdjustmentLedgerRepository x){appeals=a;ledgers=l;adjustments=x;}
 @Transactional public PointAppeal submit(Long ledgerId,String type,String reason,AuthController.AuthSession s){
  PointLedger ledger=ledgers.findById(ledgerId).orElseThrow(()->new IllegalArgumentException("积分记录不存在"));
  if(!ledger.getUserId().equals(s.userId()))throw new SecurityException("只能对本人的积分记录发起异议");
  String t=type==null?"":type.trim().toUpperCase(Locale.ROOT); if(!TYPES.contains(t))throw new IllegalArgumentException("异议类型无效");
  String r=required(reason,"异议理由",1000);
  if(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatusIn(ledgerId,s.userId(),List.of("SUBMITTED","PLANNER_PROCESSED")))throw new IllegalStateException("该积分记录已有处理中异议");
  // 已终审通过（APPROVED）后禁止再次申诉，防止同一流水被多轮申诉重复调账；
  // REJECTED 允许复议（产品保留），复议会新建一条异议记录，不影响终审留痕。
  if(appeals.existsByPointLedgerIdAndApplicantUserIdAndStatus(ledgerId,s.userId(),"APPROVED"))throw new IllegalStateException("该积分记录已完成异议终审，不可重复申诉");
  try{
   PointAppeal a=new PointAppeal();a.setPointLedgerId(ledgerId);a.setApplicantUserId(s.userId());a.setApplicantName(s.name());a.setType(t);a.setReason(r);a.setDueAt(addWorkdays(LocalDateTime.now(),3));return appeals.save(a);
  }catch(DataIntegrityViolationException e){
   // 数据库部分唯一索引（V38：SUBMITTED/PLANNER_PROCESSED 行在 (point_ledger_id, applicant_user_id) 上唯一）兜底并发双提交。
   throw new IllegalStateException("该积分记录已有处理中异议，请勿重复提交",e);
  }
 }
 @Transactional public PointAppeal plannerProcess(Long id,String decision,String comment,AuthController.AuthSession s){
  requireRole(s,"planner","admin"); PointAppeal a=get(id); if(!"SUBMITTED".equals(a.getStatus()))throw new IllegalStateException("异议当前状态不可进行企划处理");
  a.setPlannerDecision(decision(decision));a.setPlannerComment(required(comment,"处理说明",1000));a.setPlannerUserId(s.userId());a.setPlannerName(s.name());a.setPlannerProcessedAt(LocalDateTime.now());a.setStatus("PLANNER_PROCESSED");return appeals.save(a);
 }
 @Transactional public PointAppeal adminReview(Long id,String decision,String comment,Integer adjustmentPoints,AuthController.AuthSession s){
  requireRole(s,"admin");PointAppeal a=get(id);if(!"PLANNER_PROCESSED".equals(a.getStatus()))throw new IllegalStateException("异议须先完成企划处理");
  String d=decision(decision);if("APPROVE".equals(d)&&adjustmentPoints==null)throw new IllegalArgumentException("通过异议时必须提供adjustmentPoints");if(adjustmentPoints!=null&&Math.abs((long)adjustmentPoints)>100000)throw new IllegalArgumentException("调账积分绝对值不得超过100000");a.setAdminDecision(d);a.setAdminComment(required(comment,"复核说明",1000));a.setAdminUserId(s.userId());a.setAdminName(s.name());a.setAdminReviewedAt(LocalDateTime.now());a.setStatus("APPROVE".equals(d)?"APPROVED":"REJECTED");
  try{
   a=appeals.save(a);
   // 显式 flush：JPA 对已加载实体的 save 不会立即执行 SQL，唯一索引冲突会延迟到
   // 事务提交（或后续查询自动 flush）才抛出，落在 try 之外导致 500/503；
   // 这里强制立即 flush，让 V40 兜底在 try 内转换为业务异常（409）。
   appeals.flush();
  }catch(DataIntegrityViolationException e){
   // V40 approved_ledger_user_key 唯一索引兜底：同 (ledger,user) 已有一笔 APPROVED 时再次 APPROVE 被拒
   throw new IllegalStateException("该积分记录已完成异议终审，不可重复申诉",e);
  }
  if("APPROVE".equals(d)&&adjustmentPoints!=0&&adjustments.findBySourceTypeAndSourceId("APPEAL",a.getId()).isEmpty()){PointAdjustmentLedger x=new PointAdjustmentLedger();x.setUserId(a.getApplicantUserId());x.setSourceType("APPEAL");x.setSourceId(a.getId());x.setPoints(adjustmentPoints);x.setReason("积分异议终审："+a.getAdminComment());x.setCreatedBy(s.userId());adjustments.save(x);}return a;
 }
 @Transactional(readOnly=true) public List<PointAppeal> list(AuthController.AuthSession s){return "admin".equals(role(s))||"planner".equals(role(s))?appeals.findAllByOrderByCreatedAtDesc():appeals.findByApplicantUserIdOrderByCreatedAtDesc(s.userId());}
 private PointAppeal get(Long id){return appeals.findById(id).orElseThrow(()->new IllegalArgumentException("异议不存在"));}
 private String decision(String v){String d=v==null?"":v.trim().toUpperCase(Locale.ROOT);if(!Set.of("APPROVE","REJECT").contains(d))throw new IllegalArgumentException("处理决定必须为APPROVE或REJECT");return d;}
 private String required(String v,String n,int max){String x=v==null?"":v.trim();if(x.isEmpty()||x.length()>max)throw new IllegalArgumentException(n+"不能为空且不得超过"+max+"字");return x;}
 private void requireRole(AuthController.AuthSession s,String... allowed){if(Arrays.stream(allowed).noneMatch(x->x.equals(role(s))))throw new SecurityException("无权执行此操作");}
 private String role(AuthController.AuthSession s){return PermissionCatalog.normalizeRole(s.role());}
 private LocalDateTime addWorkdays(LocalDateTime start,int days){LocalDateTime value=start;int added=0;while(added<days){value=value.plusDays(1);if(value.getDayOfWeek()!=DayOfWeek.SATURDAY&&value.getDayOfWeek()!=DayOfWeek.SUNDAY)added++;}return value;}
}
