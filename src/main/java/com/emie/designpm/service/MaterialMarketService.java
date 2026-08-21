package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @Transactional
public class MaterialMarketService {
 private static final ObjectMapper MAPPER=new ObjectMapper();
 private final MaterialMarketItemRepository materials; private final ProjectRepository projects; private final UserRepository users; private final FileRecordRepository fileRecords; private final FileArchiveService fileArchive;
 private final IpOptionRepository ips; private final PointRuleRepository rules; private final PointAdjustmentLedgerRepository adjustments;
 private final NotificationWorkflowService notifications;
 public MaterialMarketService(MaterialMarketItemRepository m,ProjectRepository p,UserRepository u,FileRecordRepository f,FileArchiveService archive,IpOptionRepository i,PointRuleRepository r,PointAdjustmentLedgerRepository a,NotificationWorkflowService n){materials=m;projects=p;users=u;fileRecords=f;fileArchive=archive;ips=i;rules=r;adjustments=a;notifications=n;}
 public List<MaterialMarketItem> list(){return materials.findAllByOrderByCreatedAtDesc();}
 public MaterialMarketItem publish(Map<String,Object> b,String creatorId){
  User u=users.findByUserId(creatorId).orElseThrow(()->new IllegalArgumentException("用户不存在"));
  if(!"designer".equals(u.getRole())) throw new SecurityException("仅设计师可以发布素材");
  String title=Objects.toString(b.get("title"),"").trim(), desc=Objects.toString(b.containsKey("description")?b.get("description"):b.get("productDescription"),"").trim();
  if(title.isBlank()||desc.isBlank()) throw new IllegalArgumentException("标题和产品说明不能为空");
  String ip=Objects.toString(b.get("ipName"),""); if(ips.findByName(ip).filter(x->Boolean.TRUE.equals(x.getActive())).isEmpty()) throw new IllegalArgumentException("IP必须选择系统启用配置");
  String files=validateFiles(Objects.toString(b.containsKey("filesJson")?b.get("filesJson"):b.get("materialFiles"),""),false,5,false,creatorId);
  String referenceImages=validateFiles(Objects.toString(b.getOrDefault("referenceImagesJson","[]"),"[]"),true,6,true,creatorId);
  MaterialMarketItem m=new MaterialMarketItem();m.setTitle(title);m.setCreatorId(creatorId);m.setCreatorName(u.getName());m.setIpName(ip);m.setIpSubOptionsJson(Objects.toString(b.getOrDefault("ipSubOptions","[]"),"[]"));m.setMaterialFilesJson(files);m.setReferenceImagesJson(referenceImages);m.setProductDescription(desc);m.setProposalPptJson(Objects.toString(b.containsKey("planFileJson")?b.get("planFileJson"):b.get("proposalPpt"),null));m=materials.save(m);fileArchive.bindFilesFromJson(files,"material_market",m.getId());fileArchive.bindFilesFromJson(referenceImages,"material_market",m.getId());return m;
 }
 public MaterialMarketItem select(Long id,String actorId,String role,String plannerId){
  MaterialMarketItem m=materials.lockById(id).orElseThrow(()->new IllegalArgumentException("素材不存在或已被选中"));
  if(!"available".equals(m.getStatus())) throw new IllegalStateException("素材已被选中");
  User actor=users.findByUserId(actorId).orElseThrow(); String type="sales".equals(role)?"channel_custom":"regular";
  if(!Set.of("sales","planner","admin").contains(role)) throw new SecurityException("当前角色不能选材");
  String planner=""; String plannerName="";
  if("planner".equals(role)){planner=actorId;plannerName=actor.getName();} else if("admin".equals(role)){planner=Objects.toString(plannerId,"").trim(); if(planner.isBlank()) throw new IllegalArgumentException("请选择产品企划"); User p=users.findByUserId(planner).filter(x->"planner".equals(x.getRole())).orElseThrow(()->new IllegalArgumentException("请选择产品企划"));plannerName=p.getName();}
  Project p=new Project();p.setType(type);p.setStatus("channel_custom".equals(type)?"pending_planner":"draft");p.setPlannerId(planner);p.setPlannerName(plannerName);p.setSalesId("sales".equals(role)?actorId:null);p.setSalesName("sales".equals(role)?actor.getName():null);p.setProductName(m.getTitle());p.setProductRequirements(m.getProductDescription());p.setDescription(m.getProductDescription());p.setReferenceImagesJson(Objects.requireNonNullElse(m.getReferenceImagesJson(),"[]"));p.setAttachmentsJson(m.getMaterialFilesJson());p.setIpName(m.getIpName());p.setIpSubOptions(Objects.requireNonNullElse(m.getIpSubOptionsJson(),"[]"));p.setCreativeAuthorId(m.getCreatorId());p.setCreativeAuthorName(m.getCreatorName());p.setSource("素材广场");p.setDeadline("待定");p=projects.save(p);fileArchive.bindFilesFromJson(p.getReferenceImagesJson(),"project",p.getId());fileArchive.bindFilesFromJson(p.getAttachmentsJson(),"project",p.getId());
  m.setStatus("selected");m.setProjectId(p.getId());m.setSelectedBy(actorId);m.setSelectedAt(LocalDateTime.now());materials.save(m);
  int points=rules.findByRuleCode("MATERIAL_MARKET_LAUNCH").filter(PointRule::isEnabled).map(PointRule::getPoints).orElse(30);PointAdjustmentLedger l=new PointAdjustmentLedger();l.setUserId(m.getCreatorId());l.setSourceType("MATERIAL_MARKET");l.setSourceId(p.getId());l.setPoints(points);l.setReason("素材广场素材立项");l.setCreatedBy(actorId);adjustments.save(l);
  final Long projectId=p.getId(); final String materialTitle=m.getTitle();
  if("sales".equals(role)) {
   Map<String,String> context=Map.of("projectId",String.valueOf(projectId),"projectName",materialTitle,
     "actorName",actor.getName(),"deadline",p.getDeadline(),"projectLink","/?projectId="+projectId);
   notifications.notifyRoleAfterCommit("MATERIAL_MARKET_PLANNER_PENDING","planner","project",projectId,actorId,context);
  }
 return m;
 }
 private String validateFiles(String json,boolean images,int maxCount,boolean required,String ownerId){
  if(json==null||json.isBlank()) { if(required) throw new IllegalArgumentException(images?"参考图片不能为空":"附件不能为空"); return "[]"; }
  try{
   List<Map<String,Object>> values=MAPPER.readValue(json,new TypeReference<>(){});
   if(values.size()>maxCount) throw new IllegalArgumentException((images?"参考图片":"附件")+"最多上传"+maxCount+"个");
   List<Map<String,Object>> cleaned=new ArrayList<>();
   for(Map<String,Object> value:values){
    String storedName=Objects.toString(value.get("storedName"),"");
    FileRecord record=fileRecords.findByStoredName(storedName).orElseThrow(()->new IllegalArgumentException("文件不存在或已失效"));
    if(!ownerId.equals(record.getOwnerUserId())||record.getTargetType()!=null||record.getTargetId()!=null) throw new IllegalArgumentException("只能使用本人刚上传的文件");
    if(images?!SecurityUtil.isValidImageFile(record.getOriginalName()):!SecurityUtil.isValidAttachmentFile(record.getOriginalName())) throw new IllegalArgumentException("文件类型不支持");
    cleaned.add(Map.of("name",record.getOriginalName(),"storedName",record.getStoredName(),"size",record.getFileSize(),"url","/api/files/download/"+record.getStoredName()));
   }
   if(required&&cleaned.isEmpty()) throw new IllegalArgumentException(images?"参考图片不能为空":"附件不能为空");
   return MAPPER.writeValueAsString(cleaned);
  }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("文件信息格式不正确");}
 }
}
