package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final UserService userService;
    private final ProductCategoryRepository productCategoryRepository;
    private final SystemConfigRepository systemConfigRepository;

    public ProjectService(ProjectRepository projectRepository,
                          SubTaskRepository subTaskRepository,
                          ScoringRepository scoringRepository,
                          UserService userService,
                          ProductCategoryRepository productCategoryRepository,
                          SystemConfigRepository systemConfigRepository) {
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.userService = userService;
        this.productCategoryRepository = productCategoryRepository;
        this.systemConfigRepository = systemConfigRepository;
    }

    // ==================== Query ====================

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public Optional<Project> getProjectById(Long id) {
        return projectRepository.findById(id);
    }

    /** 获取项目列表（轻量版，不使用 JOIN FETCH tasks） */
    public List<Project> getProjectsByRoleAndUser(String role, String userId) {
        if ("admin".equals(role)) {
            return projectRepository.findAllLight();
        }
        if ("designer".equals(role) || "supplychain".equals(role)) {
            return projectRepository.findByDesignerIdLight(userId);
        }
        if ("sales".equals(role)) {
            return projectRepository.findBySalesIdLight(userId);
        }
        if ("planner".equals(role)) {
            return projectRepository.findByPlannerViewLight(userId);
        }
        return projectRepository.findBySalesIdLight(userId);
    }

    /** 设计师已参与的项目（轻量版） */
    public List<Project> getDesignerParticipatingProjects(String userId) {
        return projectRepository.findParticipatingByDesignerIdLight(userId);
    }

    /** 批量获取子任务统计（projectId → {taskCount, approvedCount}） */
    public Map<Long, int[]> getTaskCountMap(List<Project> projects) {
        if (projects == null || projects.isEmpty()) return Collections.emptyMap();
        List<Long> ids = projects.stream().map(Project::getId).collect(Collectors.toList());
        List<Object[]> rows = subTaskRepository.countTasksByProjectIds(ids);
        Map<Long, int[]> map = new HashMap<>(ids.size());
        for (Object[] row : rows) {
            Long pid = (Long) row[0];
            int total = ((Number) row[1]).intValue();
            int done = ((Number) row[2]).intValue();
            map.put(pid, new int[]{total, done});
        }
        // 没有子任务的项目也补 0
        for (Project p : projects) {
            map.putIfAbsent(p.getId(), new int[]{0, 0});
        }
        return map;
    }

    public List<Project> getProjectsByType(String type) {
        return projectRepository.findByTypeOrderByCreatedAtDesc(type);
    }

    // ==================== Create ====================

    /**
     * 验证并清理文件上传JSON，移除非法文件
     */
    private String validateAndCleanFiles(String json, boolean isImage) {
        if (json == null || json.isBlank()) return "[]";
        // 整体JSON过大直接拒绝，防止OOM
        if (json.length() > 700_000_000) return "[]"; // ~500MB原始文件总量

        final int maxCount = isImage ? 9 : 5;

        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> files = mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> cleaned = files.stream()
                .filter(f -> {
                    String name = (String) f.get("name");
                    if (name == null) return false;
                    return isImage ? SecurityUtil.isValidImageFile(name) : SecurityUtil.isValidAttachmentFile(name);
                })
                .filter(f -> {
                    // 只保留有url引用的文件（已上传到服务端）
                    String url = (String) f.get("url");
                    return url != null && !url.isEmpty();
                })
                .limit(maxCount)
                .collect(Collectors.toList());
            return mapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            return "[]";
        }
    }

    public Project createProject(Map<String, Object> body) {
        String type = (String) body.get("type");
        String plannerId = SecurityUtil.sanitizeText((String) body.get("plannerId"), 100);
        String deadline = (String) body.get("deadline");
        String productRequirements = SecurityUtil.sanitizeText((String) body.get("productRequirements"), 2000);
        String description = SecurityUtil.sanitizeText((String) body.getOrDefault("description", ""), 2000);

        // 验证并清理文件上传
        String refImagesJson = validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true);
        String attsJson = validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false);

        Project p = new Project();
        p.setType(type);
        p.setPlannerId(plannerId);
        p.setPlannerName(plannerId != null && !plannerId.isEmpty() ? userService.getUserName(plannerId) : "");

        if ("channel_custom".equals(type)) {
            p.setSalesId((String) body.get("salesId"));
            p.setSalesName(userService.getUserName((String) body.get("salesId")));
            p.setStatus("pending_planner");
        } else {
            p.setStatus("planner_accepted");
        }

        p.setDeadline(deadline);
        p.setProductRequirements(productRequirements);
        p.setDescription(description);

        // 通用字段（所有项目类型）
        String catName = (String) body.get("productCategory");
        if (catName != null && !catName.isBlank()) {
            productCategoryRepository.findByName(catName).ifPresent(p::setProductCategory);
        }
        String note = (String) body.get("productCategoryNote");
        if (note != null && !note.isBlank()) {
            p.setProductCategoryNote(SecurityUtil.sanitizeText(note, 500));
        }
        p.setTargetMarket(SecurityUtil.sanitizeText((String) body.get("targetMarket"), 100));
        String complianceStr = (String) body.get("complianceItems");
        if (complianceStr != null && !complianceStr.isBlank()) {
            p.setComplianceItems(SecurityUtil.sanitizeText(complianceStr, 500));
        }
        String priceRangeStr = (String) body.get("priceRange");
        if (priceRangeStr != null && !priceRangeStr.isBlank()) {
            p.setPriceRange(priceRangeStr);
        }

        p.setReferenceImagesJson(refImagesJson);
        p.setAttachmentsJson(attsJson);

        // Log
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String logAction = "channel_custom".equals(type)
                ? "销售提交渠道定制项目" : "产品企划新建常规品设计项目";
        p.getLogs().add(new ActivityLog(logAction, currentUser, currentRole, p));

        return projectRepository.save(p);
    }

    // ==================== Planner Accept ====================

    @Transactional
    public Project plannerAccept(Long projectId, String currentUser, String currentRole, String userId) {
        // 使用悲观锁锁定项目行，防止并发接单
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // 如果之前未指定企划，自动绑定接单的企划
        if ((p.getPlannerId() == null || p.getPlannerId().isBlank()) && userId != null && !userId.isBlank()) {
            p.setPlannerId(userId);
            p.setPlannerName(userService.getUserName(userId));
        } else if (p.getPlannerId() != null && !p.getPlannerId().isBlank() && !p.getPlannerId().equals(userId)) {
            // 已被其他企划接单
            throw new RuntimeException("该项目已被其他产品企划接单");
        }

        p.setStatus("planner_accepted");
        p.getLogs().add(new ActivityLog("产品企划接单，请添加子任务", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    // ==================== Sub-Task Management ====================

    public Project addSubTask(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        // 销售不允许创建子任务
        // 销售不允许创建子任务
        String role = (String) body.getOrDefault("currentRole", "");
        if ("sales".equals(role)) {
            throw new RuntimeException("销售无法创建子任务");
        }

        String name = SecurityUtil.sanitizeText((String) body.get("name"), 200);
        String plannedDate = (String) body.get("plannedDate");
        String designerId = SecurityUtil.sanitizeText((String) body.get("designerId"), 100);
        String details = SecurityUtil.sanitizeText((String) body.getOrDefault("details", ""), 2000);

        SubTask task = new SubTask();
        task.setName(name);
        task.setStatus("pending");
        task.setPlannedDate(plannedDate);
        task.setDesignerId(designerId);
        task.setDesignerName(userService.getUserName(designerId));
        // 设置负责人角色类型（designer / supplychain），默认 designer
        String assigneeRole = (String) body.get("assigneeRole");
        task.setAssigneeRole(assigneeRole != null && !assigneeRole.isBlank() ? assigneeRole : "designer");
        task.setDetails(details);
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        task.setProject(p);

        p.getTasks().add(task);
        if ("planner_accepted".equals(p.getStatus())) {
            p.setStatus("in_progress");
        }
        // 已完结项目添加子任务时重新激活
        if ("completed".equals(p.getStatus())) {
            p.setStatus("in_progress");
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog("添加子任务：" + name, currentUser, role, p));

        return projectRepository.save(p);
    }

    public Project updateSubTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // 销售不允许编辑子任务
        String currentRole = (String) body.getOrDefault("currentRole", "");
        if ("sales".equals(currentRole)) {
            throw new RuntimeException("销售无法编辑子任务");
        }

        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        if (body.containsKey("name")) task.setName(SecurityUtil.sanitizeText((String) body.get("name"), 200));
        if (body.containsKey("plannedDate")) task.setPlannedDate((String) body.get("plannedDate"));
        if (body.containsKey("designerId")) {
            String did = SecurityUtil.sanitizeText((String) body.get("designerId"), 100);
            task.setDesignerId(did);
            task.setDesignerName(userService.getUserName(did));
        }
        if (body.containsKey("assigneeRole")) {
            task.setAssigneeRole((String) body.get("assigneeRole"));
        }
        if (body.containsKey("details")) task.setDetails(SecurityUtil.sanitizeText((String) body.get("details"), 2000));
        if (body.containsKey("referenceImagesJson")) task.setReferenceImagesJson(validateAndCleanFiles((String) body.get("referenceImagesJson"), true));
        if (body.containsKey("attachmentsJson")) task.setAttachmentsJson(validateAndCleanFiles((String) body.get("attachmentsJson"), false));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog("编辑子任务：" + task.getName(), currentUser, currentRole, p));

        return projectRepository.save(p);
    }

    @Transactional
    public Project deleteSubTask(Long projectId, Long taskId) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        if (!List.of("pending", "pending_planner").contains(p.getStatus()) && !"pending".equals(task.getStatus())) {
            throw new RuntimeException("项目已进入工作流程，无法删除子任务");
        }

        // 删除关联的评分记录
        scoringRepository.deleteBySubTaskId(taskId);
        // 从项目中移除子任务
        p.getTasks().remove(task);
        subTaskRepository.delete(task);
        return projectRepository.save(p);
    }

    // ==================== Task Workflow ====================

    @Transactional
    public Project taskAccept(Long projectId, Long taskId, Map<String, Object> body) {
        // 锁定项目行
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        // 锁定子任务行
        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));

        // 子任务已被他人接单
        if (!"pending".equals(task.getStatus())) {
            throw new RuntimeException("该子任务已被接单或已处理");
        }

        // 如果子任务未指定设计师，自动绑定接单的设计师（防并发）
        if (task.getDesignerId() == null || task.getDesignerId().isBlank()) {
            String currentUser = (String) body.getOrDefault("currentUser", "");
            String currentRole = (String) body.getOrDefault("currentRole", "");
            String designerUserId = (String) body.get("designerUserId");
            if (designerUserId != null && !designerUserId.isBlank()) {
                task.setDesignerId(designerUserId);
                task.setDesignerName(userService.getUserName(designerUserId));
                p.getLogs().add(new ActivityLog("设计师接单：" + task.getName() + "（自动绑定" + task.getDesignerName() + "）", currentUser, currentRole, p));
            }
        } else if (!task.getDesignerId().equals(body.get("designerUserId"))) {
            // 已被其他设计师接单
            String otherName = userService.getUserName(task.getDesignerId());
            throw new RuntimeException("该子任务已被 " + (otherName != null ? otherName : "其他设计师") + " 接单");
        }

        task.setStatus("accepted");
        if (body.containsKey("plannedDate")) task.setPlannedDate((String) body.get("plannedDate"));
        p.setStatus("in_progress");

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("子任务接单：" + task.getName(), currentUser, currentRole, p));

        return projectRepository.save(p);
    }

    public Project taskDeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        task.setStatus("delivered");
        task.setActualDate((String) body.get("actualDate"));
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        // 设计师自评分（双维度：审美 + 创新）
        Double selfAesthetics = body.containsKey("selfAesthetics") ? ((Number) body.get("selfAesthetics")).doubleValue() : null;
        Double selfInnovation = body.containsKey("selfInnovation") ? ((Number) body.get("selfInnovation")).doubleValue() : null;
        task.setSelfAesthetics(selfAesthetics);
        task.setSelfInnovation(selfInnovation);
        // 兼容旧字段：取平均值
        if (selfAesthetics != null && selfInnovation != null) {
            task.setSelfScore(Math.round((selfAesthetics + selfInnovation) / 2.0 * 10.0) / 10.0);
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String selfScoreStr = selfAesthetics != null && selfInnovation != null
            ? "审美" + selfAesthetics + "/创新" + selfInnovation
            : "—";
        p.getLogs().add(new ActivityLog("子任务交付（自评" + selfScoreStr + "）：" + task.getName(), currentUser, currentRole, p));

        return projectRepository.save(p);
    }

    public Project taskRedeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        task.setStatus("delivered");
        task.setActualDate((String) body.get("actualDate"));
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        task.setReviewComments(null);
        // 设计师自评分（双维度）
        Double reAesthetics = body.containsKey("selfAesthetics") ? ((Number) body.get("selfAesthetics")).doubleValue() : null;
        Double reInnovation = body.containsKey("selfInnovation") ? ((Number) body.get("selfInnovation")).doubleValue() : null;
        task.setSelfAesthetics(reAesthetics);
        task.setSelfInnovation(reInnovation);
        if (reAesthetics != null && reInnovation != null) {
            task.setSelfScore(Math.round((reAesthetics + reInnovation) / 2.0 * 10.0) / 10.0);
        }

        // Clear all scoring records
        scoringRepository.deleteAll(scoringRepository.findBySubTaskId(taskId));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("子任务重新交付：" + task.getName(), currentUser, currentRole, p));

        return projectRepository.save(p);
    }

    public Project taskApprove(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String comments = SecurityUtil.sanitizeText((String) body.getOrDefault("comments", ""), 500);
        boolean isChannel = "channel_custom".equals(p.getType());
        Double aesthetics = body.containsKey("aesthetics") ? ((Number) body.get("aesthetics")).doubleValue() : null;
        Double innovation = body.containsKey("innovation") ? ((Number) body.get("innovation")).doubleValue() : null;

        if ("delivered".equals(task.getStatus()) && "planner".equals(currentRole)) {
            // Step 1: 企划验收 → 企划评分
            task.setStatus("planner_approved");
            task.setReviewComments(comments);
            createScoringRecord(task, "planner", "planner", aesthetics, innovation);
            p.getLogs().add(new ActivityLog("企划验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else if ("planner_approved".equals(task.getStatus()) && "sales".equals(currentRole) && isChannel) {
            // 渠道：销售验收 → 销售评分
            task.setStatus("sales_approved");
            task.setReviewComments(comments);
            createScoringRecord(task, "sales", "sales", aesthetics, innovation);
            p.getLogs().add(new ActivityLog("销售验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else if ("planner_approved".equals(task.getStatus()) && "admin".equals(currentRole) && !isChannel) {
            // 常规品：管理验收 → 管理评分
            task.setStatus("admin_approved");
            task.setReviewComments(comments);
            createScoringRecord(task, "admin", "admin", aesthetics, innovation);
            p.getLogs().add(new ActivityLog("管理验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else {
            throw new RuntimeException("当前状态无法执行验收操作");
        }

        // 检查是否所有评分已完成
        checkTaskCompletion(task, p);

        return projectRepository.save(p);
    }

    /** 创建评分记录（双维度：审美评分 + 创新评分） */
    private void createScoringRecord(SubTask task, String role, String scoreType, Double aesthetics, Double innovation) {
        ScoringRecord sr = new ScoringRecord();
        sr.setRole(role);
        sr.setScoreType(scoreType);
        sr.setAesthetics(aesthetics);
        sr.setInnovation(innovation);
        // 同时保留 score 字段兼容（取两者平均值）
        if (aesthetics != null && innovation != null) {
            sr.setScore((int) Math.round((aesthetics + innovation) / 2.0));
        }
        // 读取对应项目类型的角色权重百分比，转为小数
        String projectType = task.getProject() != null ? task.getProject().getType() : "regular";
        sr.setWeight(getScoringPct(projectType, role) / 100.0);
        sr.setSubTask(task);
        scoringRepository.save(sr);
    }

    /** 从 SystemConfig 读取评分权重百分比，按项目类型+角色 */
    private double getScoringPct(String projectType, String role) {
        String key = "scoring." + projectType + "." + role;
        return systemConfigRepository.findByConfigKey(key)
            .map(c -> { try { return Double.parseDouble(c.getConfigValue()); } catch (Exception e) { return 25.0; } })
            .orElse(25.0);
    }

    /** 从 SystemConfig 读取评分权重，不存在则返回 1.0 */
    private double getScoringWeight(String role) {
        String key = "scoring.weight." + role;
        return systemConfigRepository.findByConfigKey(key)
            .map(c -> { try { return Double.parseDouble(c.getConfigValue()); } catch (Exception e) { return 1.0; } })
            .orElse(1.0);
    }

    /** 检查子任务是否所有评分已完成 */
    private void checkTaskCompletion(SubTask task, Project project) {
        boolean isChannel = "channel_custom".equals(project.getType());
        if (isChannel) {
            // 渠道：企划评分 + 销售评分 → completed
            if ("sales_approved".equals(task.getStatus())) {
                task.setStatus("completed");
            }
        } else {
            // 常规品：企划评分 + 管理评分 → completed
            if ("admin_approved".equals(task.getStatus())) {
                task.setStatus("completed");
            }
        }
        // 检查项目是否所有子任务都已完成
        if ("completed".equals(task.getStatus())) {
            boolean allDone = project.getTasks().stream().allMatch(t -> "completed".equals(t.getStatus()));
            if (allDone) {
                project.setStatus("completed");
            }
        }
    }

    public Project taskReject(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        task.setStatus("rejected");
        String comments = SecurityUtil.sanitizeText((String) body.get("comments"), 500);
        task.setReviewComments(comments);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("子任务驳回：" + task.getName() + "（意见：" + comments + "）", currentUser, currentRole, p));

        return projectRepository.save(p);
    }

    // ==================== Scoring ====================

    public Project submitScoring(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        SubTask task = p.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new RuntimeException("子任务不存在"));

        String role = (String) body.get("role");
        Double aesthetics = body.containsKey("aesthetics") ? ((Number) body.get("aesthetics")).doubleValue() : null;
        Double innovation = body.containsKey("innovation") ? ((Number) body.get("innovation")).doubleValue() : null;

        ScoringRecord sr = scoringRepository.findBySubTaskIdAndRole(taskId, role)
                .orElseGet(() -> {
                    ScoringRecord newSr = new ScoringRecord();
                    newSr.setRole(role);
                    newSr.setSubTask(task);
                    return newSr;
                });
        sr.setAesthetics(aesthetics);
        sr.setInnovation(innovation);
        if (aesthetics != null && innovation != null) {
            sr.setScore((int) Math.round((aesthetics + innovation) / 2.0));
        }
        sr.setScoreType(role);
        if (body.containsKey("comment")) {
            sr.setComment(SecurityUtil.sanitizeText((String) body.get("comment"), 500));
        }
        scoringRepository.save(sr);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String scoreLabel = aesthetics != null && innovation != null
            ? "审美" + aesthetics + "/创新" + innovation
            : (aesthetics != null ? "审美" + aesthetics : "—");
        p.getLogs().add(new ActivityLog("子任务评分（" + role + "：" + scoreLabel + "）：" + task.getName(), currentUser, currentRole, p));

        checkTaskCompletion(task, p);
        return projectRepository.save(p);
    }

    // ==================== Terminate / Pause / Resume ====================

    public Project terminateProject(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");

        // 待企划接单：直接终止
        if ("pending_planner".equals(p.getStatus())) {
            p.setStatus("terminated");
            p.getLogs().add(new ActivityLog("项目终止", currentUser, currentRole, p));
            return projectRepository.save(p);
        }

        // 已进入工作流程：需要双方确认（仅限渠道定制单）
        boolean isChannel = "channel_custom".equals(p.getType());
        if (!isChannel) {
            // 常规品：直接终止（仅企划单方操作）
            p.setStatus("terminated");
            p.setTerminateRequester(null);
            p.getLogs().add(new ActivityLog("项目终止", currentUser, currentRole, p));
            return projectRepository.save(p);
        }

        String requester = p.getTerminateRequester();
        if (requester == null) {
            // 发起终止请求
            p.setTerminateRequester(currentRole);
            p.setStatus("pending_terminate");
            p.getLogs().add(new ActivityLog("发起终止请求", currentUser, currentRole, p));
            return projectRepository.save(p);
        }

        // 另一方确认终止
        if (requester.equals(currentRole)) {
            throw new RuntimeException("您已发起过终止请求，请等待对方确认");
        }
        p.setStatus("terminated");
        p.setTerminateRequester(null);
        p.getLogs().add(new ActivityLog("项目已终止", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    /** 取消终止（仅发起者可以取消） */
    public Project cancelTerminate(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        if (!"pending_terminate".equals(p.getStatus())) {
            throw new RuntimeException("项目不处于终止确认状态");
        }
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.setTerminateRequester(null);
        p.setStatus("in_progress");
        p.getLogs().add(new ActivityLog("已取消终止请求", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    public Project pauseProject(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        if (!List.of("pending_planner", "planner_accepted", "in_progress").contains(p.getStatus())) {
            throw new RuntimeException("当前状态不允许暂停");
        }
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.setPrePauseStatus(p.getStatus());
        p.setStatus("paused");
        p.getLogs().add(new ActivityLog("项目暂停", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    public Project resumeProject(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        if (!"paused".equals(p.getStatus())) {
            throw new RuntimeException("只有暂停中的项目可以继续");
        }
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");

        // 恢复到暂停前的状态
        String prevStatus = p.getPrePauseStatus();
        if (prevStatus != null && List.of("pending_planner", "planner_accepted", "in_progress").contains(prevStatus)) {
            p.setStatus(prevStatus);
        } else {
            // 降级：根据是否有活动任务来判断
            boolean hasActiveTasks = p.getTasks().stream().anyMatch(t -> !"pending".equals(t.getStatus()));
            p.setStatus(hasActiveTasks ? "in_progress" : "planner_accepted");
        }
        p.setPrePauseStatus(null);
        p.getLogs().add(new ActivityLog("项目继续", currentUser, currentRole, p));
        return projectRepository.save(p);
    }

    // ==================== Delete ====================

    public void deleteProject(Long projectId) {
        scoringRepository.deleteByProjectId(projectId);
        projectRepository.deleteById(projectId);
    }

    // ==================== Role Status Board ====================

    /** 获取指定角色的状态看板（销售/企划/供应链/设计师） */
    public Map<String, Object> getRoleStatus(String role) {
        List<User> users = userService.getUsersByRole(role);
        Map<String, Object> result = new LinkedHashMap<>();

        if ("designer".equals(role) || "supplychain".equals(role)) {
            // 批量查询所有用户的子任务（一次 SQL 替代 N 次）
            List<String> userIds = users.stream().map(User::getUserId).collect(Collectors.toList());
            List<SubTask> allTasks = userIds.isEmpty() ? List.of() : subTaskRepository.findByDesignerIds(userIds);
            Map<String, List<SubTask>> tasksByUser = allTasks.stream()
                    .collect(Collectors.groupingBy(SubTask::getDesignerId));

            for (User u : users) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", u.getUserId());
                info.put("name", u.getName());
                info.put("title", u.getTitle());

                List<SubTask> userTasks = tasksByUser.getOrDefault(u.getUserId(), List.of());
                List<SubTask> activeTasks = userTasks.stream()
                        .filter(t -> List.of("pending", "accepted", "rejected", "delivered").contains(t.getStatus()))
                        .collect(Collectors.toList());
                List<SubTask> completedTasks = userTasks.stream()
                        .filter(t -> "approved".equals(t.getStatus()))
                        .collect(Collectors.toList());

                info.put("activeTasks", activeTasks.stream().map(t -> {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("id", t.getId());
                    tm.put("name", t.getName());
                    tm.put("status", t.getStatus());
                    tm.put("projectId", t.getProject().getId());
                    return tm;
                }).collect(Collectors.toList()));
                info.put("completedTasks", completedTasks.size());
                info.put("busy", !activeTasks.isEmpty());
                info.put("label", "designer".equals(role) ? "设计师" : "供应链");
                result.put(u.getUserId(), info);
            }
        } else if ("sales".equals(role) || "planner".equals(role)) {
            for (User u : users) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", u.getUserId());
                info.put("name", u.getName());
                info.put("title", u.getTitle());

                List<Project> projects = "sales".equals(role)
                        ? projectRepository.findBySalesIdLight(u.getUserId())
                        : projectRepository.findByPlannerViewLight(u.getUserId());
                List<Project> activeProjects = projects.stream()
                        .filter(p -> !List.of("draft", "terminated", "completed").contains(p.getStatus()))
                        .collect(Collectors.toList());

                info.put("activeProjects", activeProjects.stream().map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", p.getId());
                    pm.put("name", p.getProductRequirements() != null ? p.getProductRequirements().substring(0, Math.min(30, p.getProductRequirements().length())) : "未命名");
                    pm.put("status", p.getStatus());
                    pm.put("type", p.getType());
                    return pm;
                }).collect(Collectors.toList()));
                info.put("completedProjects", projects.stream().filter(p -> "completed".equals(p.getStatus())).count());
                info.put("busy", !activeProjects.isEmpty());
                info.put("label", "sales".equals(role) ? "销售" : "产品企划");
                result.put(u.getUserId(), info);
            }
        }
        return result;
    }

    // 保留旧方法兼容
    public Map<String, Object> getDesignerStatus() {
        return getRoleStatus("designer");
    }

    // ==================== Project Compute Status ====================

    public String computeProjectStatus(Project p) {
        String status = p.getStatus();
        if (List.of("draft", "pending_planner", "planner_accepted", "paused", "pending_terminate", "terminated").contains(status)) {
            return status;
        }
        if (p.getTasks().isEmpty()) return status;
        // 所有子任务是否都已通过验收（兼容旧 approved 和新 completed 状态）
        boolean allApproved = p.getTasks().stream().allMatch(t ->
            "completed".equals(t.getStatus()) || "approved".equals(t.getStatus()));
        if (!allApproved) return "in_progress";
        // 所有子任务已通过 → 检查评分是否全部完成
        boolean allScored = p.getTasks().stream().allMatch(t -> isTaskFullyCompleted(t));
        return allScored ? "completed" : "completed_pending_score";
    }

    public static String computeProjectStatusStatic(Project p) {
        String status = p.getStatus();
        if (List.of("draft", "pending_planner", "planner_accepted", "paused", "pending_terminate", "terminated").contains(status)) {
            return status;
        }
        if (p.getTasks().isEmpty()) return status;
        boolean allApproved = p.getTasks().stream().allMatch(t ->
            "completed".equals(t.getStatus()) || "approved".equals(t.getStatus()));
        return allApproved ? "completed" : "in_progress";
    }

    public static Map<String, String> getProjectStatusInfo(String status) {
        return switch (status) {
            case "draft" -> Map.of("label", "草稿", "cls", "badge-pending");
            case "pending_planner" -> Map.of("label", "待企划接单", "cls", "badge-pending");
            case "planner_accepted" -> Map.of("label", "企划已接单", "cls", "badge-progress");
            case "in_progress" -> Map.of("label", "进行中", "cls", "badge-progress");
            case "paused" -> Map.of("label", "已暂停", "cls", "badge-pending");
            case "pending_terminate" -> Map.of("label", "终止确认中", "cls", "badge-rejected");
            case "terminated" -> Map.of("label", "已终止", "cls", "badge-rejected");
            case "completed" -> Map.of("label", "已完成", "cls", "badge-completed");
            case "completed_pending_score" -> Map.of("label", "待评分", "cls", "badge-pending");
            default -> Map.of("label", status, "cls", "");
        };
    }

    /**
     * 判断子任务是否真正完成（已验收 + 所有评分角色已评分）
     */
    public boolean isTaskFullyCompleted(SubTask task) {
        return "completed".equals(task.getStatus());
    }

    /**
     * 计算项目综合得分：取所有已完成子任务的加权平均分
     * 每个子任务得分 = Σ(角色平均分 × 角色权重) / Σ(权重)
     * 角色平均分 = (审美 + 创新) / 2
     */
    public Double computeProjectScore(Project project) {
        List<SubTask> tasks = project.getTasks();
        if (tasks == null || tasks.isEmpty()) return null;
        double totalScore = 0;
        int scoredCount = 0;
        for (SubTask task : tasks) {
            if (!"completed".equals(task.getStatus()) && !"approved".equals(task.getStatus())) continue;
            List<ScoringRecord> records = scoringRepository.findBySubTaskId(task.getId());
            if (records == null || records.isEmpty()) continue;
            double weightedSum = 0;
            double totalWeight = 0;
            for (ScoringRecord sr : records) {
                if (sr.getAesthetics() != null && sr.getInnovation() != null) {
                    double roleAvg = (sr.getAesthetics() + sr.getInnovation()) / 2.0;
                    weightedSum += roleAvg * sr.getWeight();
                    totalWeight += sr.getWeight();
                }
            }
            if (totalWeight > 0) {
                totalScore += (weightedSum / totalWeight);
                scoredCount++;
            }
        }
        return scoredCount > 0 ? Math.round(totalScore / scoredCount * 10.0) / 10.0 : null;
    }

    /**
     * 批量计算项目综合得分（消除 N+1 查询）
     * 一次 SQL 拉取所有项目的评分记录，内存中聚合计算
     */
    public Map<Long, Double> computeProjectScoresBatch(List<Project> projects) {
        if (projects == null || projects.isEmpty()) return Collections.emptyMap();
        List<Long> projectIds = projects.stream().map(Project::getId).collect(Collectors.toList());
        // 一次 SQL 查全部
        List<ScoringRecord> allRecords = scoringRepository.findByProjectIds(projectIds);
        // 按 project.id 分组
        Map<Long, List<ScoringRecord>> recordsByProject = new HashMap<>();
        for (ScoringRecord sr : allRecords) {
            if (sr.getSubTask() != null && sr.getSubTask().getProject() != null) {
                Long pid = sr.getSubTask().getProject().getId();
                recordsByProject.computeIfAbsent(pid, k -> new ArrayList<>()).add(sr);
            }
        }
        // 逐项目计算
        Map<Long, Double> result = new HashMap<>();
        for (Project p : projects) {
            List<SubTask> tasks = p.getTasks();
            if (tasks == null || tasks.isEmpty()) { result.put(p.getId(), null); continue; }
            List<ScoringRecord> records = recordsByProject.getOrDefault(p.getId(), List.of());
            // 按子任务分组
            Map<Long, List<ScoringRecord>> recordsByTask = new HashMap<>();
            for (ScoringRecord sr : records) {
                if (sr.getSubTask() != null) {
                    recordsByTask.computeIfAbsent(sr.getSubTask().getId(), k -> new ArrayList<>()).add(sr);
                }
            }
            double totalScore = 0;
            int scoredCount = 0;
            for (SubTask task : tasks) {
                if (!"completed".equals(task.getStatus()) && !"approved".equals(task.getStatus())) continue;
                List<ScoringRecord> taskRecords = recordsByTask.get(task.getId());
                if (taskRecords == null || taskRecords.isEmpty()) continue;
                double weightedSum = 0;
                double totalWeight = 0;
                for (ScoringRecord sr : taskRecords) {
                    if (sr.getAesthetics() != null && sr.getInnovation() != null) {
                        double roleAvg = (sr.getAesthetics() + sr.getInnovation()) / 2.0;
                        weightedSum += roleAvg * sr.getWeight();
                        totalWeight += sr.getWeight();
                    }
                }
                if (totalWeight > 0) {
                    totalScore += (weightedSum / totalWeight);
                    scoredCount++;
                }
            }
            result.put(p.getId(), scoredCount > 0 ? Math.round(totalScore / scoredCount * 10.0) / 10.0 : null);
        }
        return result;
    }

    // ==================== Pending Scoring (聚合查询) ====================

    /** 获取待评分任务列表（替代前端 N+1 次循环） */
    public List<Map<String, Object>> getPendingScoringTasks(String role, String userId) {
        List<Project> projects = getProjectsByRoleAndUser(role, userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Project p : projects) {
            String projectType = p.getType();
            boolean isChannel = "channel_custom".equals(projectType);

            for (SubTask t : p.getTasks()) {
                List<ScoringRecord> records = scoringRepository.findBySubTaskId(t.getId());
                String taskStatus = t.getStatus();

                // 判断是否待评分
                boolean isPending = false;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", t.getId());
                item.put("taskName", t.getName());
                item.put("taskStatus", taskStatus);
                item.put("projectId", p.getId());
                item.put("projectType", projectType);
                item.put("projectName", p.getProductRequirements());
                item.put("designerId", t.getDesignerId());
                item.put("designerName", t.getDesignerName());
                item.put("selfScore", t.getSelfScore());
                item.put("selfAesthetics", t.getSelfAesthetics());
                item.put("selfInnovation", t.getSelfInnovation());
                item.put("scoringRecords", records.stream().map(sr -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", sr.getRole());
                    m.put("scoreType", sr.getScoreType());
                    m.put("score", sr.getScore());
                    m.put("aesthetics", sr.getAesthetics());
                    m.put("innovation", sr.getInnovation());
                    return m;
                }).collect(Collectors.toList()));

                if ("admin".equals(role)) {
                    isPending = ("planner_approved".equals(taskStatus))
                            || ("approved".equals(taskStatus) && records.stream().anyMatch(r -> r.getAesthetics() == null));
                } else if ("sales".equals(role) && isChannel && "planner_approved".equals(taskStatus)) {
                    isPending = true;
                } else if (("designer".equals(role) || "supplychain".equals(role)) && t.getDesignerId() != null && t.getDesignerId().equals(userId)) {
                    if ("approved".equals(taskStatus) && records.stream().anyMatch(r -> r.getAesthetics() == null)) {
                        isPending = true;
                    }
                } else {
                    // planner / other roles
                    List<String> scoringRoles = isChannel ? List.of("sales", "planner") : List.of("planner");
                    if (scoringRoles.contains(role) && "approved".equals(taskStatus)) {
                        isPending = records.stream()
                                .anyMatch(r -> role.equals(r.getRole()) && r.getAesthetics() == null);
                    }
                }

                if (isPending) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    public static Map<String, String> getTaskStatusInfo(String status) {
        return switch (status) {
            case "pending" -> Map.of("label", "待接单", "cls", "badge-pending", "icon", "⏳");
            case "accepted" -> Map.of("label", "设计中", "cls", "badge-progress", "icon", "🎨");
            case "delivered" -> Map.of("label", "待验收", "cls", "badge-pending", "icon", "📤");
            case "planner_approved" -> Map.of("label", "企划已验收", "cls", "badge-progress", "icon", "✅");
            case "scoring_planner" -> Map.of("label", "待二次验收", "cls", "badge-pending", "icon", "⏳");
            case "sales_approved" -> Map.of("label", "销售已验收", "cls", "badge-progress", "icon", "✅");
            case "admin_approved" -> Map.of("label", "管理已验收", "cls", "badge-progress", "icon", "✅");
            case "approved" -> Map.of("label", "已通过", "cls", "badge-completed", "icon", "✅");
            case "completed" -> Map.of("label", "已完成", "cls", "badge-completed", "icon", "✅");
            case "rejected" -> Map.of("label", "已驳回", "cls", "badge-rejected", "icon", "↩️");
            default -> Map.of("label", status, "cls", "", "icon", "❓");
        };
    }
}
