package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service public class PointManualAdjustmentService {
 // 手动调账仅面向有积分资格的设计师（供应链不参与积分）。
 private static final String SOURCE_TYPE="MANUAL";private static final int MAX_POINTS=100000;private static final int MAX_REASON=500;private static final Set<String> ELIGIBLE_ROLES=Set.of("designer");
 private final PointAdjustmentLedgerRepository adjustments;private final UserRepository users;
 public PointManualAdjustmentService(PointAdjustmentLedgerRepository a,UserRepository u){adjustments=a;users=u;}
 @Transactional public PointAdjustmentLedger adjust(String userId,Integer points,String reason,AuthController.AuthSession s){
  requireRole(s);String uid=required(userId,"用户ID",100);User user=users.findByUserId(uid).orElse(null);if(user==null)throw new IllegalArgumentException("用户不存在");if(!ELIGIBLE_ROLES.contains(PermissionCatalog.normalizeRole(user.getRole())))throw new IllegalArgumentException("只能为设计师调账");
  if(points==null)throw new IllegalArgumentException("积分不能为空");if(points==0)throw new IllegalArgumentException("积分必须为非零整数");if(Math.abs((long)points)>MAX_POINTS)throw new IllegalArgumentException("调账积分绝对值不得超过100000");String r=required(reason,"备注",MAX_REASON);
  long nextSourceId=adjustments.maxSourceIdByType(SOURCE_TYPE)+1;PointAdjustmentLedger x=new PointAdjustmentLedger();x.setUserId(uid);x.setSourceType(SOURCE_TYPE);x.setSourceId(nextSourceId);x.setPoints(points);x.setReason(r);x.setCreatedBy(s.userId());
  try{return adjustments.save(x);}catch(DataIntegrityViolationException e){
   // (source_type, source_id) 唯一索引兜底并发窗口内的重复 sourceId。
   throw new IllegalStateException("手动调账记录冲突，请重试",e);
  }
 }
 private void requireRole(AuthController.AuthSession s){if(!"admin".equals(PermissionCatalog.normalizeRole(s.role())))throw new SecurityException("仅管理员可操作");}
 private String required(String v,String n,int max){String x=v==null?"":v.trim();if(x.isEmpty()||x.length()>max)throw new IllegalArgumentException(n+"不能为空且不得超过"+max+"字");return x;}
}
