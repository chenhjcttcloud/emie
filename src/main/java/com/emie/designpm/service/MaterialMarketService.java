package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @Transactional
public class MaterialMarketService {
 private static final ObjectMapper MAPPER=new ObjectMapper();
 private static final Set<String> MATERIAL_CATEGORIES=Set.of("id","visual","graphic");
 private final MaterialMarketItemRepository materials; private final ProjectRepository projects; private final UserRepository users; private final FileRecordRepository fileRecords; private final FileArchiveService fileArchive;
 private final IpOptionRepository ips; private final PointAdjustmentLedgerRepository adjustments; private final MaterialMarketLikeRepository likes; private final MaterialMarketAdoptionRepository adoptions;
 private final PointRuleRepository pointRules;
 private final NotificationWorkflowService notifications;
 public MaterialMarketService(MaterialMarketItemRepository m,ProjectRepository p,UserRepository u,FileRecordRepository f,FileArchiveService archive,IpOptionRepository i,PointAdjustmentLedgerRepository a,NotificationWorkflowService n,MaterialMarketLikeRepository l,MaterialMarketAdoptionRepository adoptionRepository){this(m,p,u,f,archive,i,a,n,l,adoptionRepository,null);}
 @Autowired public MaterialMarketService(MaterialMarketItemRepository m,ProjectRepository p,UserRepository u,FileRecordRepository f,FileArchiveService archive,IpOptionRepository i,PointAdjustmentLedgerRepository a,NotificationWorkflowService n,MaterialMarketLikeRepository l,MaterialMarketAdoptionRepository adoptionRepository,PointRuleRepository pointRuleRepository){materials=m;projects=p;users=u;fileRecords=f;fileArchive=archive;ips=i;adjustments=a;notifications=n;likes=l;adoptions=adoptionRepository;pointRules=pointRuleRepository;}
 public List<MaterialMarketItem> list(String actorId){
  List<MaterialMarketItem> items=materials.findAllByOrderByCreatedAtDesc();
  Set<Long> likedIds=items.isEmpty()?Set.of():likes.findByMaterialIdInAndUserId(items.stream().map(MaterialMarketItem::getId).toList(),actorId).stream().map(MaterialMarketLike::getMaterialId).collect(java.util.stream.Collectors.toSet());
  Map<Long,List<MaterialMarketAdoption>> adoptionMap=items.isEmpty()?Map.of():adoptions.findByMaterialIdInOrderByCreatedAtDesc(items.stream().map(MaterialMarketItem::getId).toList()).stream().collect(java.util.stream.Collectors.groupingBy(MaterialMarketAdoption::getMaterialId));
  return items.stream().map(item->{item.setLikedByCurrentUser(likedIds.contains(item.getId()));item.setAdoptions(enrichAdoptions(adoptionMap.getOrDefault(item.getId(),List.of())));return withSelectorName(item);}).toList();
 }
 public MaterialMarketItem detail(Long id,String actorId){MaterialMarketItem item=materials.findById(id).orElseThrow(()->new NoSuchElementException("素材不存在"));item.setLikedByCurrentUser(likes.findByMaterialIdAndUserId(id,actorId).isPresent());item.setAdoptions(enrichAdoptions(adoptions.findByMaterialIdOrderByCreatedAtDesc(id)));return withSelectorName(item);}
 private List<MaterialMarketAdoption> enrichAdoptions(List<MaterialMarketAdoption> values){
  values.forEach(value->{projects.findById(value.getProjectId()).ifPresent(project->value.setProjectCode(project.getProjectCode()));users.findByUserId(value.getSelectedBy()).ifPresent(user->value.setSelectedByName(user.getName()));});return values;
 }
 private MaterialMarketItem withSelectorName(MaterialMarketItem item){
  if(item.getSelectedBy()!=null) item.setSelectedByName(users.findByUserId(item.getSelectedBy()).map(User::getName).orElse(item.getSelectedBy()));
  if(item.getProjectId()!=null) projects.findById(item.getProjectId()).ifPresent(p -> item.setProjectCode(p.getProjectCode()));
  return item;
 }
 public MaterialMarketItem publish(Map<String,Object> b,String creatorId){
  User u=users.findByUserId(creatorId).orElseThrow(()->new IllegalArgumentException("用户不存在"));
  if(!"designer".equals(u.getRole())) throw new SecurityException("仅设计师可以发布素材");
  String title=Objects.toString(b.get("title"),"").trim(), desc=Objects.toString(b.containsKey("description")?b.get("description"):b.get("productDescription"),"").trim();
  if(title.isBlank()||desc.isBlank()) throw new IllegalArgumentException("标题和产品说明不能为空");
  String category=validCategory(b.get("category"));
  String ip=Objects.toString(b.get("ipName"),""); if(ips.findByName(ip).filter(x->Boolean.TRUE.equals(x.getActive())).isEmpty()) throw new IllegalArgumentException("IP必须选择系统启用配置");
  String files=validateFiles(Objects.toString(b.containsKey("filesJson")?b.get("filesJson"):b.get("materialFiles"),""),false,5,false,creatorId);
  String referenceImages=validateFiles(Objects.toString(b.getOrDefault("referenceImagesJson","[]"),"[]"),true,6,true,creatorId);
  MaterialMarketItem m=new MaterialMarketItem();m.setTitle(title);m.setCategory(category);m.setCreatorId(creatorId);m.setCreatorName(u.getName());m.setIpName(ip);m.setIpSubOptionsJson(Objects.toString(b.getOrDefault("ipSubOptions","[]"),"[]"));m.setMaterialFilesJson(files);m.setReferenceImagesJson(referenceImages);m.setProductDescription(desc);m.setProposalPptJson(Objects.toString(b.containsKey("planFileJson")?b.get("planFileJson"):b.get("proposalPpt"),null));m=materials.save(m);fileArchive.bindFilesFromJson(files,"material_market",m.getId());fileArchive.bindFilesFromJson(referenceImages,"material_market",m.getId());return m;
 }
 public MaterialMarketItem update(Long id,Map<String,Object> b,String actorId){
  MaterialMarketItem m=materials.findById(id).orElseThrow(()->new NoSuchElementException("素材不存在"));
  ensureDesignerOwner(m,actorId);
  if(!"available".equals(m.getStatus())) throw new IllegalStateException("已采纳或已下架的素材不能修改");
  if(b.containsKey("title")){String v=Objects.toString(b.get("title"),"").trim(); if(v.isBlank()) throw new IllegalArgumentException("标题不能为空"); m.setTitle(v);}
  if(b.containsKey("category")) m.setCategory(validCategory(b.get("category")));
  if(b.containsKey("description")||b.containsKey("productDescription")){String v=Objects.toString(b.containsKey("description")?b.get("description"):b.get("productDescription"),"").trim(); if(v.isBlank()) throw new IllegalArgumentException("产品说明不能为空"); m.setProductDescription(v);}
  if(b.containsKey("ipName")){String v=Objects.toString(b.get("ipName"),""); if(ips.findByName(v).filter(x->Boolean.TRUE.equals(x.getActive())).isEmpty()) throw new IllegalArgumentException("IP必须选择系统启用配置"); m.setIpName(v);}
  if(b.containsKey("ipSubOptions")) m.setIpSubOptionsJson(Objects.toString(b.get("ipSubOptions"),"[]"));
  if(b.containsKey("filesJson")||b.containsKey("materialFiles")){String v=Objects.toString(b.containsKey("filesJson")?b.get("filesJson"):b.get("materialFiles"),""); m.setMaterialFilesJson(validateFilesForUpdate(v,false,5,false,actorId,id));}
  if(b.containsKey("referenceImagesJson")) m.setReferenceImagesJson(validateFilesForUpdate(Objects.toString(b.get("referenceImagesJson"),"[]"),true,6,true,actorId,id));
  if(b.containsKey("planFileJson")||b.containsKey("proposalPpt")) m.setProposalPptJson(Objects.toString(b.containsKey("planFileJson")?b.get("planFileJson"):b.get("proposalPpt"),null));
  m=materials.save(m); fileArchive.bindFilesFromJson(m.getMaterialFilesJson(),"material_market",m.getId()); fileArchive.bindFilesFromJson(m.getReferenceImagesJson(),"material_market",m.getId()); return m;
 }
 private String validCategory(Object value){String category=Objects.toString(value,"").trim().toLowerCase(Locale.ROOT);if(!MATERIAL_CATEGORIES.contains(category)) throw new IllegalArgumentException("请选择ID、视觉或平面分类");return category;}
 public MaterialMarketItem withdraw(Long id,String actorId){MaterialMarketItem m=materials.findById(id).orElseThrow(()->new NoSuchElementException("素材不存在")); ensureDesignerOwner(m,actorId); if(!"available".equals(m.getStatus())) throw new IllegalStateException("已采纳的素材不能下架"); m.setStatus("withdrawn"); return materials.save(m);}
 public void delete(Long id,String actorId){MaterialMarketItem m=materials.findById(id).orElseThrow(()->new NoSuchElementException("素材不存在")); ensureDesignerOwner(m,actorId); if(adoptions.existsByMaterialId(id)||m.getProjectId()!=null) throw new IllegalStateException("已有采纳项目的素材不能删除，请保留项目关联记录"); likes.deleteAllByMaterialId(id);adoptions.deleteAllByMaterialId(id);materials.delete(m);}
 private void ensureDesignerOwner(MaterialMarketItem m,String actorId){if(!Objects.equals(m.getCreatorId(),actorId)) throw new SecurityException("仅素材作者可以操作"); users.findByUserId(actorId).filter(u->"designer".equals(u.getRole())).orElseThrow(()->new SecurityException("仅设计师可以操作素材"));}
 public MaterialMarketItem adopt(Long id,String actorId,String role,String plannerId,String adoptionType){
  String adoption=Objects.toString(adoptionType,"").trim().toLowerCase(Locale.ROOT);
  if(!Set.of("direct","design").contains(adoption)) throw new IllegalArgumentException("请选择直接采纳或设计采纳");
  MaterialMarketItem m=materials.lockById(id).orElseThrow(()->new IllegalArgumentException("素材不存在"));
  if("withdrawn".equals(m.getStatus())) throw new IllegalStateException("已下架素材不能采纳");
  if(adoptions.existsByMaterialIdAndAdoptionType(id,adoption)) throw new IllegalStateException("该素材已完成过"+("direct".equals(adoption)?"直接采纳":"设计采纳"));
  User actor=users.findByUserId(actorId).orElseThrow(); String type="sales".equals(role)?"channel_custom":"regular";
  if(!Set.of("sales","planner","admin").contains(role)) throw new SecurityException("当前角色不能采纳素材");
  String planner=""; String plannerName="";
  if("planner".equals(role)){planner=actorId;plannerName=actor.getName();}
  Project p=new Project();p.setType(type);p.setStatus("channel_custom".equals(type)?"pending_planner":"draft");p.setPlannerId(planner);p.setPlannerName(plannerName);p.setSalesId("sales".equals(role)?actorId:null);p.setSalesName("sales".equals(role)?actor.getName():null);p.setProductName(m.getTitle());p.setProductRequirements(m.getProductDescription());p.setDescription(m.getProductDescription());p.setReferenceImagesJson(Objects.requireNonNullElse(m.getReferenceImagesJson(),"[]"));p.setAttachmentsJson(m.getMaterialFilesJson());p.setIpName(m.getIpName());p.setIpSubOptions(Objects.requireNonNullElse(m.getIpSubOptionsJson(),"[]"));p.setCreativeAuthorId(m.getCreatorId());p.setCreativeAuthorName(m.getCreatorName());p.setSource("素材广场");p.setDeadline("待定");p=projects.save(p);fileArchive.bindFilesFromJson(p.getReferenceImagesJson(),"project",p.getId());fileArchive.bindFilesFromJson(p.getAttachmentsJson(),"project",p.getId());
  m.setStatus("selected");m.setAdoptionType(adoption);m.setProjectId(p.getId());m.setSelectedBy(actorId);m.setSelectedAt(LocalDateTime.now());materials.save(m);
  MaterialMarketAdoption adoptionRecord=new MaterialMarketAdoption();adoptionRecord.setMaterialId(m.getId());adoptionRecord.setProjectId(p.getId());adoptionRecord.setAdoptionType(adoption);adoptionRecord.setSelectedBy(actorId);adoptions.save(adoptionRecord);
  String ruleCode="direct".equals(adoption)?"M2":"M1";
  int points=pointRules==null? ("direct".equals(adoption)?20:10) : pointRules.findByRuleCode(ruleCode).filter(PointRule::isEnabled).map(PointRule::getPoints).orElseThrow(()->new IllegalStateException("素材广场采纳积分规则未配置或已停用"));
  PointAdjustmentLedger l=new PointAdjustmentLedger();l.setUserId(m.getCreatorId());l.setSourceType("MATERIAL_MARKET");l.setSourceId(p.getId());l.setPoints(points);l.setReason("direct".equals(adoption)?"素材广场直接采纳奖励":"素材广场设计采纳奖励");l.setCreatedBy(actorId);adjustments.save(l);
  final Long projectId=p.getId(); final String materialTitle=m.getTitle();
  if("sales".equals(role)) {
   Map<String,String> context=Map.of("projectId",String.valueOf(projectId),"projectName",materialTitle,
     "actorName",actor.getName(),"deadline",p.getDeadline(),"projectLink","/?projectId="+projectId);
   notifications.notifyRoleAfterCommit("MATERIAL_MARKET_PLANNER_PENDING","planner","project",projectId,actorId,context);
  }
 return m;
 }
 public Map<String,Object> toggleLike(Long id,String actorId){
  MaterialMarketItem m=materials.lockById(id).orElseThrow(()->new NoSuchElementException("素材不存在"));
  if(Objects.equals(m.getCreatorId(),actorId)) throw new IllegalStateException("不能给自己的作品点赞");
  Optional<MaterialMarketLike> existing=likes.findByMaterialIdAndUserId(id,actorId);
  boolean liked;
  int count=Math.max(0,Objects.requireNonNullElse(m.getLikeCount(),0));
  if(existing.isPresent()){likes.delete(existing.get());count=Math.max(0,count-1);liked=false;}
  else{MaterialMarketLike like=new MaterialMarketLike();like.setMaterialId(id);like.setUserId(actorId);likes.save(like);count++;liked=true;}
  m.setLikeCount(count);materials.save(m);
  return Map.of("liked",liked,"likeCount",count);
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
 private String validateFilesForUpdate(String json,boolean images,int maxCount,boolean required,String ownerId,Long materialId){
  if(json==null||json.isBlank()){if(required)throw new IllegalArgumentException(images?"参考图片不能为空":"附件不能为空");return "[]";}
  try{
   List<Map<String,Object>> values=MAPPER.readValue(json,new TypeReference<>(){}); if(values.size()>maxCount)throw new IllegalArgumentException((images?"参考图片":"附件")+"最多上传"+maxCount+"个");
   List<Map<String,Object>> cleaned=new ArrayList<>();
   for(Map<String,Object> value:values){String storedName=Objects.toString(value.get("storedName"),""); FileRecord record=fileRecords.findByStoredName(storedName).orElseThrow(()->new IllegalArgumentException("文件不存在或已失效"));
    boolean existing=record.getTargetType()==null&&record.getTargetId()==null || "material_market".equals(record.getTargetType())&&Objects.equals(record.getTargetId(),materialId);
    if(!ownerId.equals(record.getOwnerUserId())||!existing)throw new IllegalArgumentException("只能使用本人素材文件");
    if(images?!SecurityUtil.isValidImageFile(record.getOriginalName()):!SecurityUtil.isValidAttachmentFile(record.getOriginalName()))throw new IllegalArgumentException("文件类型不支持");
    cleaned.add(Map.of("name",record.getOriginalName(),"storedName",record.getStoredName(),"size",record.getFileSize(),"url","/api/files/download/"+record.getStoredName()));
   }
   if(required&&cleaned.isEmpty())throw new IllegalArgumentException(images?"参考图片不能为空":"附件不能为空"); return MAPPER.writeValueAsString(cleaned);
  }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("文件信息格式不正确");}
 }
}
