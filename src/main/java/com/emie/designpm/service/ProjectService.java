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

    public ProjectService(ProjectRepository projectRepository,
                          SubTaskRepository subTaskRepository,
                          ScoringRepository scoringRepository,
                          UserService userService) {
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.userService = userService;
    }

    // ==================== Query ====================

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public Optional<Project> getProjectById(Long id) {
        return projectRepository.findById(id);
    }

    public List<Project> getProjectsByRoleAndUser(String role, String userId) {
        if ("superior".equals(role) || "admin".equals(role)) {
            return projectRepository.findAllWithTasks();
        }
        if ("designer".equals(role)) {
            return projectRepository.findByDesignerId(userId);
        }
        if ("sales".equals(role)) {
            // 销售：只能看到自己发布的全部项目（渠道定制 + 常规品）
            return projectRepository.findBySalesId(userId);
        }
        if ("planner".equals(role)) {
            // 企划：看到指派给自己的 + 未指定企划的渠道定制单
            return projectRepository.findByPlannerView(userId);
        }
        return projectRepository.findBySalesId(userId);
    }

    /** 设计师已参与的项目（已接单子任务，排除待认领的） */
    public List<Project> getDesignerParticipatingProjects(String userId) {
        return projectRepository.findParticipatingByDesignerId(userId);
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
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("添加子任务：" + name, currentUser, currentRole, p));

        return projectRepository.save(p);
    }

    public Project updateSubTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
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
        if (body.containsKey("details")) task.setDetails(SecurityUtil.sanitizeText((String) body.get("details"), 2000));
        if (body.containsKey("referenceImagesJson")) task.setReferenceImagesJson(validateAndCleanFiles((String) body.get("referenceImagesJson"), true));
        if (body.containsKey("attachmentsJson")) task.setAttachmentsJson(validateAndCleanFiles((String) body.get("attachmentsJson"), false));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
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

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("子任务交付：" + task.getName(), currentUser, currentRole, p));

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

        // Clear scoring
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
        boolean isChannel = "channel_custom".equals(p.getType());

        // 评分字段（渠道定制单：审批时同时评分）
        Double aesthetics = body.containsKey("aesthetics") ? ((Number) body.get("aesthetics")).doubleValue() : null;
        Double innovation = body.containsKey("innovation") ? ((Number) body.get("innovation")).doubleValue() : null;

        if (isChannel && "planner".equals(currentRole) && "delivered".equals(task.getStatus())) {
            // 渠道定制单：企划确认验收 + 评分 → 进入 planner_approved 状态，等待销售确认评分
            task.setStatus("planner_approved");
            task.setReviewComments(SecurityUtil.sanitizeText((String) body.getOrDefault("comments", "企划验收通过"), 500));
            // 创建两条评分记录（企划带分值，销售待评分）
            createScoringRecord(task, "planner", aesthetics, innovation, p.getType());
            createScoringRecord(task, "sales", null, null, p.getType());
            p.getLogs().add(new ActivityLog("子任务企划确认通过并评分（待销售确认评分）：" + task.getName(), currentUser, currentRole, p));
        } else if (isChannel && "sales".equals(currentRole) && "planner_approved".equals(task.getStatus())) {
            // 渠道定制单：销售确认验收 + 评分 → 最终 approved
            task.setStatus("approved");
            task.setReviewComments(SecurityUtil.sanitizeText((String) body.getOrDefault("comments", "销售确认通过"), 500));
            // 更新销售的评分记录（企划的已存在）
            createScoringRecord(task, "sales", aesthetics, innovation, p.getType());
            p.getLogs().add(new ActivityLog("子任务销售确认通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else {
            // 常规品（或其他情况）：直接通过，评分后续单独填写
            task.setStatus("approved");
            task.setReviewComments(SecurityUtil.sanitizeText((String) body.getOrDefault("comments", "验收通过"), 500));
            p.getLogs().add(new ActivityLog("子任务验收通过：" + task.getName(), currentUser, currentRole, p));
            initEmptyScoringRecords(task, p.getType());
        }

        // 只有最终 approved 状态才检查项目是否完结
        if ("approved".equals(task.getStatus())) {
            boolean allApproved = p.getTasks().stream().allMatch(t -> "approved".equals(t.getStatus()));
            if (allApproved) p.setStatus("completed");
        }

        return projectRepository.save(p);
    }

    private void createScoringRecord(SubTask task, String role, Double aesthetics, Double innovation, String projectType) {
        boolean isChannel = "channel_custom".equals(projectType);
        double weight = isChannel ? (role.equals("planner") ? 0.5 : 0.5) : 1.0;

        // 检查评分记录是否已存在（渠道定制单：销售确认时，记录已在企划评分时创建）
        ScoringRecord sr = scoringRepository.findBySubTaskIdAndRole(task.getId(), role)
                .orElseGet(() -> {
                    ScoringRecord newSr = new ScoringRecord();
                    newSr.setRole(role);
                    newSr.setSubTask(task);
                    return newSr;
                });
        sr.setAesthetics(aesthetics);
        sr.setInnovation(innovation);
        sr.setWeight(weight);
        scoringRepository.save(sr);
    }

    private void initEmptyScoringRecords(SubTask task, String projectType) {
        boolean isChannel = "channel_custom".equals(projectType);
        List<String> scorers = isChannel
                ? List.of("sales", "planner")
                : List.of("planner");
        Map<String, Double> weightsMap = isChannel
                ? Map.of("sales", 0.5, "planner", 0.5)
                : Map.of("planner", 1.0);
        for (String role : scorers) {
            ScoringRecord sr = new ScoringRecord();
            sr.setRole(role);
            sr.setAesthetics(null);
            sr.setInnovation(null);
            sr.setWeight(weightsMap.getOrDefault(role, 0.25));
            sr.setSubTask(task);
            scoringRepository.save(sr);
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
        Double aesthetics = ((Number) body.get("aesthetics")).doubleValue();
        Double innovation = ((Number) body.get("innovation")).doubleValue();

        ScoringRecord sr = scoringRepository.findBySubTaskIdAndRole(taskId, role)
                .orElseThrow(() -> new RuntimeException("评分记录不存在"));

        sr.setAesthetics(aesthetics);
        sr.setInnovation(innovation);

        if (body.containsKey("weights")) {
            @SuppressWarnings("unchecked")
            Map<String, Double> newWeights = (Map<String, Double>) body.get("weights");
            List<ScoringRecord> allRecords = scoringRepository.findBySubTaskId(taskId);
            for (ScoringRecord record : allRecords) {
                if (newWeights.containsKey(record.getRole())) {
                    record.setWeight(newWeights.get(record.getRole()));
                }
            }
            scoringRepository.saveAll(allRecords);
        } else {
            scoringRepository.save(sr);
        }

        // Check if all done
        List<ScoringRecord> allRecords = scoringRepository.findBySubTaskId(taskId);
        boolean allDone = allRecords.stream().allMatch(r -> r.getAesthetics() != null && r.getInnovation() != null);

        if (allDone) {
            // 检查所有子任务是否都已验收且评分完成
            boolean allTasksApproved = p.getTasks().stream().allMatch(t -> "approved".equals(t.getStatus()));
            boolean allApprovedScored = p.getTasks().stream()
                .filter(t -> "approved".equals(t.getStatus()))
                .allMatch(t -> {
                    List<ScoringRecord> records = scoringRepository.findBySubTaskId(t.getId());
                    return !records.isEmpty() && records.stream().allMatch(r -> r.getAesthetics() != null && r.getInnovation() != null);
                });
            if (allTasksApproved && allApprovedScored && !p.getTasks().isEmpty()) {
                p.setStatus("completed");
            }
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("子任务评分：" + task.getName(), currentUser, currentRole, p));

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

    // ==================== Designer Status ====================

    public Map<String, Object> getDesignerStatus() {
        List<User> designers = userService.getUsersByRole("designer");
        Map<String, Object> result = new LinkedHashMap<>();

        for (User d : designers) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", d.getUserId());
            info.put("name", d.getName());
            info.put("title", d.getTitle());

            List<SubTask> activeTasks = subTaskRepository.findByDesignerId(d.getUserId()).stream()
                    .filter(t -> List.of("pending", "accepted", "rejected", "delivered").contains(t.getStatus()))
                    .collect(Collectors.toList());

            List<SubTask> completedTasks = subTaskRepository.findByDesignerId(d.getUserId()).stream()
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
            result.put(d.getUserId(), info);
        }

        return result;
    }

    // ==================== Project Compute Status ====================

    public String computeProjectStatus(Project p) {
        String status = p.getStatus();
        if (List.of("draft", "pending_planner", "planner_accepted", "paused", "pending_terminate", "terminated").contains(status)) {
            return status;
        }
        if (p.getTasks().isEmpty()) return status;
        // 所有子任务是否都已通过验收
        boolean allApproved = p.getTasks().stream().allMatch(t -> "approved".equals(t.getStatus()));
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
        boolean allApproved = p.getTasks().stream().allMatch(t -> "approved".equals(t.getStatus()));
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
        if (!"approved".equals(task.getStatus())) return false;
        List<ScoringRecord> records = scoringRepository.findBySubTaskId(task.getId());
        if (records.isEmpty()) return false;
        return records.stream().allMatch(r -> r.getAesthetics() != null && r.getInnovation() != null);
    }

    public static Map<String, String> getTaskStatusInfo(String status) {
        return switch (status) {
            case "pending" -> Map.of("label", "待接单", "cls", "badge-pending", "icon", "⏳");
            case "accepted" -> Map.of("label", "设计中", "cls", "badge-progress", "icon", "🎨");
            case "delivered" -> Map.of("label", "待验收", "cls", "badge-pending", "icon", "📤");
            case "planner_approved" -> Map.of("label", "待评分", "cls", "badge-pending", "icon", "⏳");
            case "approved" -> Map.of("label", "已通过", "cls", "badge-completed", "icon", "✅");
            case "rejected" -> Map.of("label", "已驳回", "cls", "badge-rejected", "icon", "↩️");
            default -> Map.of("label", status, "cls", "", "icon", "❓");
        };
    }
}
