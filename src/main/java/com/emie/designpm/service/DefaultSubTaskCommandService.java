package com.emie.designpm.service;

import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import com.emie.designpm.util.SecurityUtil;
import com.emie.designpm.util.ProjectAccessPolicy;
import com.emie.designpm.controller.AuthController;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Service
@Transactional
public class DefaultSubTaskCommandService implements SubTaskCommandService {
    private void validateCustomPriceRange(String value) {
        try {
            double price = Double.parseDouble(value.trim());
            if (!Double.isFinite(price) || price < 0 || price > 1000 || Math.round(price * 100) != price * 100) {
                throw new IllegalArgumentException("参考零售价必须在0到1,000之间，最多两位小数");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参考零售价必须在0到1,000之间，最多两位小数");
        }
    }
    private static final Logger log = LoggerFactory.getLogger(DefaultSubTaskCommandService.class);
    private static final Object PROJECT_CODE_LOCK = new Object();

    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final SubTaskDeliveryVersionRepository deliveryVersionRepository;
    private final UserService userService;
    private final ProductCategoryRepository productCategoryRepository;
    private final IpOptionRepository ipOptionRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final SyncQueueService syncQueueService;
    private final FileArchiveService fileArchiveService;
    private final ProjectAccessService projectAccessService;
    private final NotificationWorkflowService notificationWorkflowService;
    private SubTaskAssignmentPolicy subTaskAssignmentPolicy;
    private SubTaskInputPolicy subTaskInputPolicy;
    private PointsService pointsService;
    private DesignerMarketEligibilityRepository marketEligibilityRepository;
    private TaskWithdrawalRepository taskWithdrawalRepository;
    private PointAdjustmentLedgerRepository pointAdjustmentLedgerRepository;
    private PointLedgerRepository pointLedgerRepository;
    private PointAppealRepository pointAppealRepository;
    private NotificationRepository notificationRepository;
    private FileRecordRepository fileRecordRepository;
    private DesignRequirementScoringService designRequirementScoringService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultSubTaskCommandService(ProjectRepository projectRepository,
                          SubTaskRepository subTaskRepository,
                          ScoringRepository scoringRepository,
                          SubTaskDeliveryVersionRepository deliveryVersionRepository,
                          UserService userService,
                          ProductCategoryRepository productCategoryRepository,
                          IpOptionRepository ipOptionRepository,
                          SystemConfigRepository systemConfigRepository,
                          SyncQueueService syncQueueService,
                          FileArchiveService fileArchiveService,
                          ProjectAccessService projectAccessService, NotificationWorkflowService notificationWorkflowService) {
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.deliveryVersionRepository = deliveryVersionRepository;
        this.userService = userService;
        this.productCategoryRepository = productCategoryRepository;
        this.ipOptionRepository = ipOptionRepository;
        this.systemConfigRepository = systemConfigRepository;
        this.syncQueueService = syncQueueService;
        this.fileArchiveService = fileArchiveService;
        this.projectAccessService = projectAccessService;
        this.notificationWorkflowService = notificationWorkflowService;
    }

    /** Optional setter keeps existing lightweight unit-test construction compatible. */
    @Autowired(required = false)
    void setPointsService(PointsService pointsService) {
        this.pointsService = pointsService;
    }
    @Autowired(required = false)
    void setMarketEligibilityRepository(DesignerMarketEligibilityRepository repository) { this.marketEligibilityRepository = repository; }
    @Autowired(required = false)
    void setTaskWithdrawalRepository(TaskWithdrawalRepository repository) { this.taskWithdrawalRepository = repository; }
    @Autowired(required = false)
    void setPointAdjustmentLedgerRepository(PointAdjustmentLedgerRepository repository) { this.pointAdjustmentLedgerRepository = repository; }
    @Autowired(required = false)
    void setPointLedgerRepository(PointLedgerRepository repository) { this.pointLedgerRepository = repository; }
    @Autowired(required = false)
    void setPointAppealRepository(PointAppealRepository repository) { this.pointAppealRepository = repository; }
    @Autowired(required = false)
    void setNotificationRepository(NotificationRepository repository) { this.notificationRepository = repository; }
    @Autowired(required = false)
    void setFileRecordRepository(FileRecordRepository repository) { this.fileRecordRepository = repository; }
    @Autowired(required = false)
    void setDesignRequirementScoringService(DesignRequirementScoringService service) { this.designRequirementScoringService = service; }
    @Autowired(required = false)
    void setSubTaskAssignmentPolicy(SubTaskAssignmentPolicy policy) { this.subTaskAssignmentPolicy = policy; }
    @Autowired(required = false)
    void setSubTaskInputPolicy(SubTaskInputPolicy policy) { this.subTaskInputPolicy = policy; }

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

    public Project addSubTask(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        String role = (String) body.getOrDefault("currentRole", "");
        if (!List.of("planner", "admin").contains(role)) {
            throw new RuntimeException("仅企划或管理员可创建子任务");
        }
        if ("planner".equals(role) && !isProjectPlanner(p, body)) {
            throw new RuntimeException("仅项目负责人企划可创建子任务");
        }

        String name = SecurityUtil.sanitizeText((String) body.get("name"), 200);
        String plannedDate = (String) body.get("plannedDate");
        String designerId = SecurityUtil.sanitizeText((String) body.get("designerId"), 100);
        boolean publishToMarket = Boolean.TRUE.equals(body.get("publishToMarket"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("publishToMarket")));
        String details = SecurityUtil.sanitizeText((String) body.getOrDefault("details", ""), 2000);
        String workflowStage = SecurityUtil.sanitizeText((String) body.get("workflowStage"), 30);
        if (!ProjectWorkflowService.STAGES.contains(workflowStage)) {
            throw new RuntimeException("请选择有效的子任务所属阶段");
        }
        String pointRuleCode = (String) body.get("pointRuleCode");
        String difficultyCode = (String) body.get("difficultyCode");
        if (pointRuleCode == null || pointRuleCode.isBlank()) {
            throw new IllegalArgumentException("请选择积分规则");
        }
        if (difficultyCode == null || difficultyCode.isBlank()) {
            throw new IllegalArgumentException("请选择难度档位");
        }

        SubTask task = new SubTask();
        task.setName(name);
        task.setStatus("pending");
        task.setWorkflowStage(workflowStage);
        task.setPlannedDate(plannedDate);
        task.setDesignerId(designerId);
        task.setDesignerName(userService.getUserName(designerId));
        task.setPublisherId((String) body.get("currentUserId"));
        task.setPublisherName((String) body.getOrDefault("currentUser", ""));
        task.setPublisherRole(role);
        if (pointsService != null) {
            pointsService.bindRuleSnapshot(task, pointRuleCode, difficultyCode);
        }
        task.setRequiredSkillTagsJson(subTaskInputPolicy == null ? validateSkillTags(body.get("requiredSkillTags")) : subTaskInputPolicy.skillTags(body.get("requiredSkillTags")));
        task.setCollaboratorAllocationsJson(subTaskInputPolicy == null ? validateCollaboratorAllocations(body.get("collaboratorAllocations"), designerId) : subTaskInputPolicy.collaboratorAllocations(body.get("collaboratorAllocations"), designerId));
        task.setMilestoneMonth(subTaskInputPolicy == null ? validateMilestoneMonth(body.get("milestoneMonth")) : subTaskInputPolicy.milestoneMonth(body.get("milestoneMonth")));
        task.setAssignmentReason(SecurityUtil.sanitizeText((String) body.get("assignmentReason"), 500));
        // 设置负责人角色类型（designer / supplychain / planner / sales），默认 designer
        String assigneeRole = (String) body.get("assigneeRole");
        task.setAssigneeRole(assigneeRole != null && !assigneeRole.isBlank() ? assigneeRole : "designer");
        if (publishToMarket) {
            if (!"designer".equals(task.getAssigneeRole())) {
                throw new RuntimeException("只有设计师子任务可以发布到接单市场");
            }
            if (designerId != null && !designerId.isBlank()) {
                throw new RuntimeException("发布到接单市场时不能指定设计师");
            }
            task.setAllocationStatus("market_open");
            task.setMarketPublishedAt(LocalDateTime.now());
        } else {
            if (designerId == null || designerId.isBlank()) {
                throw new RuntimeException("请选择子任务负责人或发布到接单市场");
            }
            task.setAllocationStatus("direct_assigned");
        }
        validateSubTaskAssignee(designerId, task.getAssigneeRole());
        // 设计师不再按任务分类或能力标签限制；所有设计师均可承接设计师类子任务。
        task.setDetails(details);
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        task.setProject(p);

        boolean firstSubTask = p.getTasks().isEmpty();
        // 先显式持久化子任务，确保后续文件绑定和提交后通知始终使用真实任务 ID。
        // 仅依赖 Project.tasks 的级联保存时，新任务在提交后回调注册阶段仍可能没有 ID，
        // 从而让 notification_events.aggregate_id 的非空约束反向影响创建接口。
        subTaskRepository.saveAndFlush(task);
        if (task.getId() == null) {
            throw new IllegalStateException("子任务保存失败，请稍后重试");
        }
        p.getTasks().add(task);
        if (firstSubTask) {
            p.setWorkflowStage(workflowStage);
            p.setWorkflowStatus("current");
        }
        if ("planner_accepted".equals(p.getStatus())) {
            p.setStatus("in_progress");
        }
        // 已完结项目添加子任务时重新激活
        if ("completed".equals(p.getStatus())) {
            p.setStatus("in_progress");
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog((publishToMarket ? "发布接单市场子任务：" : "添加子任务：") + name,
                currentUser, role, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        // 统一按负责人 ID 通知，designerId 兼容设计、供应链、销售、产品推广等负责人类型。
        if (task.getDesignerId() != null && !task.getDesignerId().isBlank()) {
            safeNotifyAfterCommit("TASK_ASSIGNED", task.getDesignerId(), "sub_task", task.getId(),
                    (String) body.getOrDefault("currentUserId", ""), notificationContext(saved, task, currentUser, ""));
        }
        return saved;
    }

    private void validateSubTaskAssignee(String userId, String assigneeRole) {
        if (subTaskAssignmentPolicy != null) {
            subTaskAssignmentPolicy.validate(userId, assigneeRole);
            return;
        }
        String normalizedRole = normalizeAssigneeRole(assigneeRole);
        if (!List.of("designer", "supplychain", "planner", "sales", "promotion").contains(normalizedRole)) {
            throw new RuntimeException("不支持的子任务负责人类型");
        }
        if (userId == null || userId.isBlank()) return;
        User assignee = userService.getUserByUserId(userId);
        if (assignee == null || !normalizedRole.equals(normalizeAssigneeRole(assignee.getRole()))) {
            throw new RuntimeException("子任务负责人和负责人类型不匹配");
        }
    }

    private String normalizeAssigneeRole(String role) {
        if (role == null) return "";
        if ("promotion".equalsIgnoreCase(role) || "product_promotion".equalsIgnoreCase(role)
                || "product-promotion".equalsIgnoreCase(role)) return "promotion";
        return role;
    }

    public Project updateSubTask(Long projectId, Long taskId, Map<String, Object> body) {
        // 与抢单、撤回保持相同的 project -> subtask 锁序，避免编辑覆盖并发抢单结果。
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        // 销售不允许编辑子任务
        String currentRole = (String) body.getOrDefault("currentRole", "");
        if (!List.of("planner", "admin").contains(currentRole)) {
            throw new RuntimeException("仅企划或管理员可编辑子任务");
        }
        if ("planner".equals(currentRole) && !isProjectPlanner(p, body)) {
            throw new RuntimeException("仅项目负责人企划可编辑子任务");
        }

        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }

        Map<String, Object> before = snapshotSubTask(task);

        if (body.containsKey("name")) task.setName(SecurityUtil.sanitizeText((String) body.get("name"), 200));
        if (body.containsKey("workflowStage")) {
            String workflowStage = SecurityUtil.sanitizeText((String) body.get("workflowStage"), 30);
            if (!ProjectWorkflowService.STAGES.contains(workflowStage)) {
                throw new RuntimeException("请选择有效的子任务所属阶段");
            }
            task.setWorkflowStage(workflowStage);
        }
        if (body.containsKey("plannedDate")) task.setPlannedDate((String) body.get("plannedDate"));
        if (body.containsKey("designerId")) {
            String did = SecurityUtil.sanitizeText((String) body.get("designerId"), 100);
            if ((did == null || did.isBlank())
                    && !("pending".equals(task.getStatus())
                    && List.of("market_open", "withdrawn").contains(task.getAllocationStatus()))) {
                throw new RuntimeException("已指派或已领取的子任务不能清空负责人");
            }
            if (!"pending".equals(task.getStatus()) && !Objects.equals(did, task.getDesignerId())) {
                throw new RuntimeException("子任务开始执行后不能更换负责人");
            }
            task.setDesignerId(did);
            task.setDesignerName(userService.getUserName(did));
            if (did != null && !did.isBlank()) {
                task.setAllocationStatus("direct_assigned");
            }
        }
        if (body.containsKey("assigneeRole")) {
            String assigneeRole = (String) body.get("assigneeRole");
            // 与 addSubTask 对齐：null/空值按设计师处理；别名（如"设计师"）落库标准值 designer。
            task.setAssigneeRole(assigneeRole != null && !assigneeRole.isBlank()
                    && !"designer".equals(PermissionCatalog.normalizeRole(assigneeRole)) ? assigneeRole : "designer");
        }
        if (body.containsKey("pointRuleCode") || body.containsKey("difficultyCode")) {
            if (!"pending".equals(task.getStatus())) {
                throw new RuntimeException("子任务开始执行后不能修改积分规则或难度");
            }
            if (pointsService == null) throw new RuntimeException("积分规则服务暂不可用，请稍后重试");
            String ruleCode = body.containsKey("pointRuleCode")
                    ? (String) body.get("pointRuleCode") : task.getPointRuleCode();
            String difficultyCode = body.containsKey("difficultyCode")
                    ? (String) body.get("difficultyCode") : task.getDifficultyCode();
            pointsService.bindRuleSnapshot(task, ruleCode, difficultyCode);
        }
        if (body.containsKey("requiredSkillTags")) {
            if (!"pending".equals(task.getStatus())) {
                throw new RuntimeException("子任务开始执行后不能修改能力要求");
            }
            task.setRequiredSkillTagsJson(subTaskInputPolicy == null ? validateSkillTags(body.get("requiredSkillTags")) : subTaskInputPolicy.skillTags(body.get("requiredSkillTags")));
        }
        if (body.containsKey("collaboratorAllocations") || body.containsKey("milestoneMonth")) {
            if (!"pending".equals(task.getStatus())) throw new RuntimeException("任务开始后不能修改合作比例或里程碑月份");
            if (body.containsKey("collaboratorAllocations")) {
                task.setCollaboratorAllocationsJson(subTaskInputPolicy == null ? validateCollaboratorAllocations(body.get("collaboratorAllocations"), task.getDesignerId()) : subTaskInputPolicy.collaboratorAllocations(body.get("collaboratorAllocations"), task.getDesignerId()));
            }
            if (body.containsKey("milestoneMonth")) task.setMilestoneMonth(subTaskInputPolicy == null ? validateMilestoneMonth(body.get("milestoneMonth")) : subTaskInputPolicy.milestoneMonth(body.get("milestoneMonth")));
        }
        if (body.containsKey("assignmentReason")) task.setAssignmentReason(SecurityUtil.sanitizeText((String) body.get("assignmentReason"), 500));
        if ("market_open".equals(task.getAllocationStatus())
                && (!"designer".equals(task.getAssigneeRole())
                || (task.getDesignerId() != null && !task.getDesignerId().isBlank()))) {
            throw new RuntimeException("开放市场任务必须保持设计师类型且不能指定负责人");
        }
        validateSubTaskAssignee(task.getDesignerId(), task.getAssigneeRole() == null ? "designer" : task.getAssigneeRole());
        if (body.containsKey("details")) task.setDetails(SecurityUtil.sanitizeText((String) body.get("details"), 2000));
        if (body.containsKey("referenceImagesJson")) task.setReferenceImagesJson(validateAndCleanFiles((String) body.get("referenceImagesJson"), true));
        if (body.containsKey("attachmentsJson")) task.setAttachmentsJson(validateAndCleanFiles((String) body.get("attachmentsJson"), false));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        Map<String, Object> after = snapshotSubTask(task);
        p.getLogs().add(new ActivityLog("编辑子任务：" + task.getName(), currentUser, currentRole, p,
                "sub_task", task.getId(), toJson(before), toJson(after), changedFields(before, after)));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        return saved;
    }

    private Map<String, Object> snapshotSubTask(SubTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", task.getName());
        data.put("status", task.getStatus());
        data.put("workflowStage", task.getWorkflowStage());
        data.put("plannedDate", task.getPlannedDate());
        data.put("designerId", task.getDesignerId());
        data.put("designerName", task.getDesignerName());
        data.put("allocationStatus", task.getAllocationStatus());
        data.put("pointRuleCode", task.getPointRuleCode());
        data.put("difficultyCode", task.getDifficultyCode());
        data.put("difficultyMultiplierSnapshot", task.getDifficultyMultiplierSnapshot());
        data.put("basePointSnapshot", task.getBasePointSnapshot());
        data.put("qualityBonusThresholdSnapshot", task.getQualityBonusThresholdSnapshot());
        data.put("qualityBonusRatioSnapshot", task.getQualityBonusRatioSnapshot());
        data.put("qualityTopThresholdSnapshot", task.getQualityTopThresholdSnapshot());
        data.put("qualityTopRatioSnapshot", task.getQualityTopRatioSnapshot());
        data.put("maxTotalMultiplierSnapshot", task.getMaxTotalMultiplierSnapshot());
        data.put("collaboratorAllocationsJson", task.getCollaboratorAllocationsJson());
        data.put("milestoneMonth", task.getMilestoneMonth());
        data.put("assignmentReason", task.getAssignmentReason());
        data.put("countInPerformanceSnapshot", task.getCountInPerformanceSnapshot());
        data.put("requiredSkillTagsJson", task.getRequiredSkillTagsJson());
        data.put("assigneeRole", task.getAssigneeRole());
        data.put("details", task.getDetails());
        return data;
    }

    private Map<String, String> notificationContext(Project project, SubTask task, String actor, String reason) {
        Map<String, String> context = new HashMap<>();
        context.put("projectName", project.getProductName());
        context.put("deadline", task != null ? task.getPlannedDate() : project.getDeadline());
        context.put("actorName", actor == null || actor.isBlank() ? "系统" : actor);
        context.put("projectLink", "/?projectId=" + project.getId());
        if (task != null) {
            context.put("taskName", task.getName());
            context.put("taskLink", "/?projectId=" + project.getId() + "&taskId=" + task.getId());
        }
        if (reason != null && !reason.isBlank()) context.put("reason", reason);
        return context;
    }

    private String validateIpSubOptions(String submittedJson, IpOption ipOption) {
        List<String> configured;
        List<String> selected;
        try {
            configured = objectMapper.readValue(Optional.ofNullable(ipOption.getSubOptionsJson()).orElse("[]"), new TypeReference<List<String>>() {});
            selected = objectMapper.readValue(Optional.ofNullable(submittedJson).orElse("[]"), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("二级IP选项格式无效");
        }
        if (configured.isEmpty()) return null;
        if (selected.isEmpty()) throw new RuntimeException("请选择二级IP选项");
        if ("single".equals(ipOption.getSubOptionSelectionMode()) && selected.size() != 1) {
            throw new RuntimeException("该IP的二级选项仅允许单选");
        }
        if (selected.stream().anyMatch(value -> value == null || !configured.contains(value))) {
            throw new RuntimeException("请选择有效的二级IP选项");
        }
        try {
            return objectMapper.writeValueAsString(selected.stream().distinct().toList());
        } catch (Exception e) {
            throw new RuntimeException("二级IP选项保存失败");
        }
    }

    private void safeNotify(String eventType, String recipientUserId, String aggregateType, Long aggregateId,
                            String actorUserId, Map<String, String> context) {
        try {
            notificationWorkflowService.notifyUser(eventType, recipientUserId, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("通知创建失败但业务操作继续: eventType={}, aggregate={}#{}", eventType, aggregateType, aggregateId, e);
        }
    }

    private void safeNotifyAfterCommit(String eventType, String recipientUserId, String aggregateType, Long aggregateId,
                                       String actorUserId, Map<String, String> context) {
        try {
            notificationWorkflowService.notifyUserAfterCommit(
                    eventType, recipientUserId, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("提交后通知注册失败但业务操作继续: eventType={}, aggregate={}#{}",
                    eventType, aggregateType, aggregateId, e);
        }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; }
    }

    private String changedFields(Map<String, Object> before, Map<String, Object> after) {
        return toJson(before.keySet().stream().filter(k -> !Objects.equals(before.get(k), after.get(k))).toList());
    }

    @Transactional
    public Project deleteSubTask(Long projectId, Long taskId) {
        // 锁序与全项目一致：project → subtask。锁内重新加载并重校验状态，
        // 避免与 taskAccept 抢单并发时快照校验通过、DELETE 阻塞后误删刚被认领的任务。
        Project p = lockProject(projectId);
        if ("paused".equals(p.getStatus())) {
            throw new RuntimeException("项目已暂停，无法删除子任务");
        }
        SubTask task = lockSubTask(projectId, taskId);
        if (!List.of("pending", "pending_planner").contains(p.getStatus()) && !"pending".equals(task.getStatus())) {
            throw new RuntimeException("项目已进入工作流程，无法删除子任务");
        }

        // 锁内按 FK 依赖顺序清理子任务关联数据，避免 DELETE 子任务时触发外键违例：
        // 退单调账（TASK_WITHDRAWAL，按 withdrawalId）→ 退单记录（FK→sub_tasks）→ 交付版本（FK→sub_tasks）→ 评分（FK→sub_tasks）。
        List<TaskWithdrawal> withdrawals = taskWithdrawalRepository == null ? List.of()
                : taskWithdrawalRepository.findBySubTaskIdIn(List.of(taskId));
        List<Long> withdrawalIds = withdrawals.stream().map(TaskWithdrawal::getId).toList();
        if (!withdrawalIds.isEmpty() && pointAdjustmentLedgerRepository != null) {
            pointAdjustmentLedgerRepository.deleteProjectRelated(List.of(), withdrawalIds);
        }
        if (!withdrawalIds.isEmpty() && taskWithdrawalRepository != null) {
            taskWithdrawalRepository.deleteBySubTaskIds(List.of(taskId));
        }
        deliveryVersionRepository.deleteBySubTaskIds(List.of(taskId));

        // 删除关联的评分记录，并同步删除飞书记录
        List<ScoringRecord> scoringRecords = scoringRepository.findBySubTaskId(taskId);
        scoringRepository.deleteAll(scoringRecords);
        // 从项目中移除子任务
        p.getTasks().remove(task);
        subTaskRepository.delete(task);
        return projectRepository.save(p);
    }

    // ==================== Task Workflow ====================

    private Project lockProject(Long projectId) {
        return projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
    }

    /** All task workflow mutations must acquire locks in project -> subtask order. */
    private SubTask lockSubTask(Long projectId, Long taskId) {
        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }
        return task;
    }

    @Transactional
    public Project taskAccept(Long projectId, Long taskId, Map<String, Object> body) {
        // 锁定项目行
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        // 锁定子任务行
        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));

        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }

        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String designerUserId = (String) body.get("designerUserId");

        if (designerUserId == null || designerUserId.isBlank()) {
            throw new RuntimeException("当前登录用户无效，无法接单");
        }
        if (task.getAssigneeRole() != null && !task.getAssigneeRole().isBlank()
                && !normalizeAssigneeRole(task.getAssigneeRole()).equals(normalizeAssigneeRole(currentRole))) {
            throw new RuntimeException("当前角色无法接此子任务");
        }

        // 子任务已被他人接单
        if (!"pending".equals(task.getStatus())) {
            throw new RuntimeException("该子任务已被接单或已处理");
        }

        if (task.getDesignerId() == null || task.getDesignerId().isBlank()) {
            validateMarketClaimConstraints(task, designerUserId);
        }

        // 如果子任务未指定设计师，自动绑定接单的设计师（防并发）
        if (task.getDesignerId() == null || task.getDesignerId().isBlank()) {
            if (!"market_open".equals(task.getAllocationStatus())) {
                throw new RuntimeException("该子任务未开放接单");
            }
            if (designerUserId != null && !designerUserId.isBlank()) {
                task.setDesignerId(designerUserId);
                task.setDesignerName(userService.getUserName(designerUserId));
                task.setAllocationStatus("claimed");
                task.setClaimedAt(LocalDateTime.now());
                p.getLogs().add(new ActivityLog("设计师接单：" + task.getName() + "（自动绑定" + task.getDesignerName() + "）", currentUser, currentRole, p));
            }
        } else if (!task.getDesignerId().equals(designerUserId)) {
            // 已被其他设计师接单
            String otherName = userService.getUserName(task.getDesignerId());
            throw new RuntimeException("该子任务已被 " + (otherName != null ? otherName : "其他设计师") + " 接单");
        }

        task.setStatus("accepted");
        if (body.containsKey("plannedDate")) task.setPlannedDate((String) body.get("plannedDate"));
        p.setStatus("in_progress");

        p.getLogs().add(new ActivityLog("子任务接单：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_ASSIGNED", task.getDesignerId(), "sub_task", task.getId(),
                (String) body.getOrDefault("currentUserId", ""), notificationContext(saved, task, currentUser, ""));
        return saved;
    }

    private void validateMarketClaimConstraints(SubTask task, String designerUserId) {
        if (!"market_open".equals(task.getAllocationStatus())) return;
        if (marketEligibilityRepository != null) marketEligibilityRepository.findByUserId(designerUserId).ifPresent(eligibility -> {
            if (eligibility.isSuspended()) throw new RuntimeException("开放接单资格已暂停至" + eligibility.getSuspendedUntil() + "，原因：" + Optional.ofNullable(eligibility.getReason()).orElse("违规处理"));
        });
        String code = Optional.ofNullable(task.getPointRuleCode()).orElse("").trim().toUpperCase();
        if (code.startsWith("A") || code.startsWith("B")) {
            long activeMainTasks = subTaskRepository.countActiveMainTasksByCategory(designerUserId, "A")
                    + subTaskRepository.countActiveMainTasksByCategory(designerUserId, "B");
            int maxMainTasks = positiveIntConfig("points.claim.max_main_tasks", 5);
            if (activeMainTasks >= maxMainTasks) {
                throw new RuntimeException("当前A/B类主任务已达上限（" + maxMainTasks + "个），请完成现有任务后再接单");
            }
        }

        // 接单不再按能力标签或任务分类限制；历史标签字段保留，仅用于兼容旧数据展示。
    }

    private void validateDesignCategoryEligibility(SubTask task, String designerUserId) {
        String configured = systemConfigRepository.findByConfigKey("points.user.skills." + designerUserId)
                .map(SystemConfig::getConfigValue).orElse("[]");
        validateDesignCategoryEligibility(task, designerUserId, parseSkillTags(configured));
    }

    private void validateDesignCategoryEligibility(SubTask task, String designerUserId, Set<String> actual) {
        String ruleCode = Optional.ofNullable(task.getPointRuleCode()).orElse("").toUpperCase(Locale.ROOT);
        if ("B1".equals(ruleCode) && !actual.contains("ID")) throw new RuntimeException("B1原创任务仅具备ID能力标签的设计师可接");
        if (ruleCode.startsWith("B") && !"B1".equals(ruleCode) && actual.stream().noneMatch(tag -> Set.of("ID", "视觉").contains(tag))) {
            throw new RuntimeException("产品设计类任务仅具备ID或视觉能力标签的设计师可接");
        }
    }

    private int positiveIntConfig(String key, int fallback) {
        return systemConfigRepository.findByConfigKey(key).map(SystemConfig::getConfigValue)
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> {
                    try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
                }).filter(value -> value > 0).orElse(fallback);
    }

    private String validateSkillTags(Object raw) {
        if (raw == null) return null;
        Collection<?> values;
        if (raw instanceof Collection<?> collection) {
            values = collection;
        } else if (raw instanceof String json && !json.isBlank()) {
            try { values = objectMapper.readValue(json, new TypeReference<List<Object>>() {}); }
            catch (Exception e) { throw new RuntimeException("能力标签格式无效"); }
        } else if (raw instanceof String) {
            return null;
        } else {
            throw new RuntimeException("能力标签格式无效");
        }
        List<String> normalized = values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> SecurityUtil.sanitizeText(value, 40))
                .filter(Objects::nonNull)
                .distinct().limit(20).toList();
        try { return normalized.isEmpty() ? null : objectMapper.writeValueAsString(normalized); }
        catch (Exception e) { throw new RuntimeException("能力标签保存失败"); }
    }

    private Set<String> parseSkillTags(String json) {
        if (json == null || json.isBlank()) return Collections.emptySet();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                    .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("忽略格式错误的能力标签配置");
            return Collections.emptySet();
        }
    }

    private String validateMilestoneMonth(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        String month = String.valueOf(raw).trim();
        try { java.time.YearMonth.parse(month); return month; }
        catch (Exception e) { throw new RuntimeException("里程碑月份格式应为YYYY-MM"); }
    }

    private String validateCollaboratorAllocations(Object raw, String primaryUserId) {
        if (raw == null || String.valueOf(raw).isBlank() || "[]".equals(String.valueOf(raw).trim())) return null;
        List<Map<String, Object>> rows;
        try {
            rows = raw instanceof Collection<?> collection
                    ? objectMapper.convertValue(collection, new TypeReference<List<Map<String, Object>>>() {})
                    : objectMapper.readValue(String.valueOf(raw), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) { throw new RuntimeException("合作成员比例格式无效"); }
        Set<String> users = new LinkedHashSet<>();
        int total = 0;
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String userId = SecurityUtil.sanitizeText(String.valueOf(row.getOrDefault("userId", "")), 100);
            int ratio;
            try { ratio = Integer.parseInt(String.valueOf(row.get("ratio"))); }
            catch (Exception e) { throw new RuntimeException("合作比例必须是整数百分比"); }
            if (userId == null || userId.isBlank() || userId.equals(primaryUserId) || !users.add(userId)) {
                throw new RuntimeException("合作成员不能重复或与主负责人相同");
            }
            if (ratio <= 0 || ratio >= 100) throw new RuntimeException("单个合作比例必须在1%到99%之间");
            User collaborator = userService.getUserByUserId(userId);
            if (collaborator == null || !"designer".equals(normalizeAssigneeRole(collaborator.getRole()))) {
                throw new RuntimeException("合作成员必须是有效设计师");
            }
            total += ratio;
            normalized.add(Map.of("userId", userId, "name", collaborator.getName(), "ratio", ratio));
        }
        if (total >= 100) throw new RuntimeException("合作成员比例合计必须小于100%，剩余比例归主负责人");
        try { return objectMapper.writeValueAsString(normalized); }
        catch (Exception e) { throw new RuntimeException("合作比例保存失败"); }
    }

    @Transactional
    public Project withdrawMarketTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        String role = (String) body.getOrDefault("currentRole", "");
        if (!List.of("planner", "admin").contains(role)) throw new RuntimeException("仅企划或管理员可撤回市场任务");
        if ("planner".equals(role) && !isProjectPlanner(p, body)) throw new RuntimeException("仅项目负责人企划可撤回市场任务");
        SubTask task = subTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) throw new RuntimeException("子任务不属于当前项目");
        if (!"market_open".equals(task.getAllocationStatus()) || !"pending".equals(task.getStatus())) {
            throw new RuntimeException("该任务已被领取或不在接单市场");
        }
        task.setAllocationStatus("withdrawn");
        p.getLogs().add(new ActivityLog("撤回接单市场子任务：" + task.getName(),
                (String) body.getOrDefault("currentUser", ""), role, p));
        return projectRepository.saveAndFlush(p);
    }

    /** 设计师退单：接单后一小时内免费，超时按任务基础分及累计退单次数比例扣分。 */
    @Transactional
    public Project withdrawAcceptedTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        SubTask task = lockSubTask(projectId, taskId);
        String userId = String.valueOf(body.getOrDefault("currentUserId", ""));
        if (userId.isBlank() || !userId.equals(task.getDesignerId())) throw new RuntimeException("仅当前负责人可退单");
        if (!"accepted".equals(task.getStatus())) throw new RuntimeException("只有已接单且未交付的任务可以退单");
        if (taskWithdrawalRepository == null) throw new RuntimeException("退单服务未就绪");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime claimed = task.getClaimedAt() == null ? now : task.getClaimedAt();
        long elapsed = Math.max(0, java.time.Duration.between(claimed, now).toMinutes());
        // 行锁读取市场资格，防同一设计师并发退单时 violation_count 读改写丢失更新。
        // 非设计师任务不涉及市场资格：不读取也不更新资格违规（P2-4）。
        DesignerMarketEligibility eligibility = !"designer".equals(task.getAssigneeRole()) || marketEligibilityRepository == null ? null
                : marketEligibilityRepository.findByUserIdForUpdate(userId)
                        .orElseGet(() -> { DesignerMarketEligibility x = new DesignerMarketEligibility(); x.setUserId(userId); return x; });
        int previous = eligibility == null ? 0 : Optional.ofNullable(eligibility.getViolationCount()).orElse(0);
        long freeMinutes = positiveLongConfig("points.withdrawal.free_minutes", 60);
        int suspendCount = positiveIntConfig("points.withdrawal.suspend_count", 3);
        double perWithdrawalRate = boundedDoubleConfig("points.withdrawal.penalty_rate", 10d) / 100d;
        int suspendDays = positiveIntConfig("points.withdrawal.suspend_days", 7);
        double ratio = elapsed <= freeMinutes ? 0d : Math.min(1d, perWithdrawalRate * (previous + 1));
        int base = (int)Math.round(Optional.ofNullable(task.getBasePointSnapshot()).orElse(0) * Optional.ofNullable(task.getDifficultyMultiplierSnapshot()).orElse(1d));
        int penalty = (int)Math.ceil(base * ratio);
        // 积分仅面向设计师任务：供应链等其它负责人类型的退单不扣分（P2-3），事件 penaltyPoints=0、reason 走免罚文案。
        if (!"designer".equals(task.getAssigneeRole())) penalty = 0;
        TaskWithdrawal event = new TaskWithdrawal(); event.setSubTaskId(taskId); event.setUserId(userId); event.setElapsedMinutes(elapsed); event.setPenaltyRatio(ratio); event.setPenaltyPoints(penalty); event.setReason(penalty <= 0 ? "接单1小时内退单（免罚）" : "接单超1小时退单，按累计次数比例扣分");
        taskWithdrawalRepository.save(event);
        // 积分仅面向设计师任务：penalty 已对非设计师置 0，仅设计师任务可能产生积分扣减（调账），退单一律记录。
        if (penalty > 0 && pointAdjustmentLedgerRepository != null) {
            PointAdjustmentLedger adjustment = new PointAdjustmentLedger(); adjustment.setUserId(userId); adjustment.setSourceType("TASK_WITHDRAWAL"); adjustment.setSourceId(event.getId()); adjustment.setPoints(-penalty); adjustment.setReason(event.getReason()); adjustment.setCreatedBy(userId); pointAdjustmentLedgerRepository.save(adjustment);
        }
        if (eligibility != null) {
            eligibility.setViolationCount(previous + 1); eligibility.setReason("退单累计" + (previous + 1) + "次");
            if (previous + 1 >= suspendCount) eligibility.setSuspendedUntil(now.plusDays(suspendDays));
            eligibility.setUpdatedBy(userId); marketEligibilityRepository.save(eligibility);
        }
        task.setDesignerId(null); task.setDesignerName(null); task.setClaimedAt(null); task.setStatus("pending"); task.setAllocationStatus("market_open");
        p.getLogs().add(new ActivityLog("设计师退单：" + task.getName() + "，扣分" + penalty, String.valueOf(body.getOrDefault("currentUser", userId)), "designer", p));
        return projectRepository.saveAndFlush(p);
    }

    /** 企划取消已接单但尚未交付的任务：释放负责人，保留任务记录供重新派发。 */
    @Transactional
    public Project cancelAcceptedTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        String role = String.valueOf(body.getOrDefault("currentRole", ""));
        if (!List.of("planner", "admin").contains(role)) throw new RuntimeException("仅企划或管理员可取消接单");
        if ("planner".equals(role) && !isProjectPlanner(p, body)) throw new RuntimeException("仅项目负责人企划可取消接单");
        SubTask task = lockSubTask(projectId, taskId);
        if (!"accepted".equals(task.getStatus())) throw new RuntimeException("只有已接单且未交付的任务可以取消接单");
        task.setDesignerId(null);
        task.setDesignerName(null);
        task.setClaimedAt(null);
        task.setStatus("pending");
        task.setAllocationStatus("direct_assigned");
        p.getLogs().add(new ActivityLog("企划取消接单：" + task.getName(),
                String.valueOf(body.getOrDefault("currentUser", "")), role, p));
        return projectRepository.saveAndFlush(p);
    }

    private long positiveLongConfig(String key, long fallback) {
        try { return Math.max(0, Long.parseLong(systemConfigRepository.findByConfigKey(key).map(SystemConfig::getConfigValue).orElse(String.valueOf(fallback)).trim())); }
        catch (Exception e) { return fallback; }
    }
    private double boundedDoubleConfig(String key, double fallback) {
        try { return Math.min(100d, Math.max(0d, Double.parseDouble(systemConfigRepository.findByConfigKey(key).map(SystemConfig::getConfigValue).orElse(String.valueOf(fallback)).trim()))); }
        catch (Exception e) { return fallback; }
    }

    public Project taskDeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可交付");
        }

        String submittedActualDate = (String) body.get("actualDate");
        task.setStatus("delivered");
        task.setActualDate(null);
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        // 设计师自评分（总分100分，整数）
        Integer selfScore = body.containsKey("selfScore") ? ((Number) body.get("selfScore")).intValue() : null;
        if (selfScore != null && (selfScore < 1 || selfScore > 100)) {
            selfScore = null;
        }
        task.setSelfScore(selfScore != null ? selfScore.doubleValue() : null);
        resetReviewWorkflow(task);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        saveDeliveryVersion(task, "initial", "首次交付", submittedActualDate, currentUserId, currentUser, currentRole);
        String selfScoreStr = selfScore != null ? selfScore.toString() : "—";
        p.getLogs().add(new ActivityLog("子任务交付（自评" + selfScoreStr + "）：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_DELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, ""));
        if ("channel_custom".equals(p.getType()) && p.getSalesId() != null && !p.getSalesId().isBlank()
                && !p.getSalesId().equals(currentUserId)) {
            safeNotifyAfterCommit("TASK_DELIVERED", p.getSalesId(), "sub_task", task.getId(), currentUserId,
                    notificationContext(p, task, currentUser, "销售关联项目已收到设计交付成果"));
        }
        return saved;
    }

    /** 负责人将已交付成果正式送审；仅设计师和供应链任务需要此一步。 */
    public Project taskSubmitReview(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        SubTask task = lockSubTask(projectId, taskId);
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        String role = (String) body.getOrDefault("currentRole", "");
        if (!"planner".equals(role)) throw new RuntimeException("仅产品企划可送审");
        if (!Objects.equals(currentUserId, p.getPlannerId())) throw new RuntimeException("当前用户不是该项目负责人企划，无法送审");
        if (!List.of("designer", "supplychain").contains(task.getAssigneeRole())) throw new RuntimeException("仅设计师和供应链子任务需要送审");
        if (!"delivered".equals(task.getStatus())) throw new RuntimeException("当前子任务不在待送审状态");
        task.setStatus("submitted_for_review");
        task.setSubmittedForReviewAt(LocalDateTime.now());
        if (pointsService != null) pointsService.awardBaseSubmission(task);
        String user = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog("子任务送审：" + task.getName(), user, role, p));
        Project saved = projectRepository.saveAndFlush(p);
        safeNotifyAfterCommit("TASK_SUBMITTED_FOR_REVIEW", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, user, ""));
        return saved;
    }

    public Project taskRedeliver(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可重新交付");
        }

        String submittedActualDate = (String) body.get("actualDate");
        task.setStatus("delivered");
        task.setActualDate(null);
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        String previousReviewComments = task.getReviewComments();
        task.setReviewComments(null);
        // 设计师自评分（总分100分，整数）
        Integer reScore = body.containsKey("selfScore") ? ((Number) body.get("selfScore")).intValue() : null;
        if (reScore != null && (reScore < 1 || reScore > 100)) {
            reScore = null;
        }
        task.setSelfScore(reScore != null ? reScore.doubleValue() : null);

        // 重新交付后，两级审核都回到待审核状态；操作日志继续保留历史过程。
        resetReviewWorkflow(task);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String changeSummary = SecurityUtil.sanitizeText(
                (String) body.getOrDefault("changeSummary", "根据修改要求重新交付"), 500);
        saveDeliveryVersion(task, "redelivery", changeSummary, submittedActualDate, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务重新交付：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.save(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_REDELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, previousReviewComments));
        if ("channel_custom".equals(p.getType()) && p.getSalesId() != null && !p.getSalesId().isBlank()) {
            safeNotifyAfterCommit("TASK_REDELIVERED", p.getSalesId(), "sub_task", task.getId(), currentUserId,
                    notificationContext(p, task, currentUser, previousReviewComments));
        }
        return saved;
    }

    /** 被驳回负责人确认开始修改，任务回到执行中。 */
    public Project taskConfirmRevision(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        SubTask task = lockSubTask(projectId, taskId);
        String userId = (String) body.getOrDefault("currentUserId", "");
        if (!userId.equals(task.getDesignerId())) throw new RuntimeException("仅当前子任务负责人可确认修改");
        if (!"rejected".equals(task.getStatus())) throw new RuntimeException("当前子任务不是已驳回状态");
        task.setStatus("accepted");
        String user = (String) body.getOrDefault("currentUser", "");
        String role = (String) body.getOrDefault("currentRole", "");
        p.getLogs().add(new ActivityLog("确认修改子任务：" + task.getName(), user, role, p));
        return projectRepository.saveAndFlush(p);
    }

    public Project taskCorrectDelivery(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("当前项目状态不允许修正交付");
        }
        SubTask task = lockSubTask(projectId, taskId);
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (currentUserId.isBlank() || !currentUserId.equals(task.getDesignerId())) {
            throw new RuntimeException("仅当前子任务负责人可修正交付");
        }
        if (!List.of("delivered", "planner_approved", "sales_approved", "admin_approved").contains(task.getStatus())) {
            throw new RuntimeException("当前子任务状态不允许主动修正；已完成任务需由管理员重新开放");
        }
        String changeSummary = SecurityUtil.sanitizeText((String) body.get("changeSummary"), 500);
        if (changeSummary == null || changeSummary.isBlank()) {
            throw new RuntimeException("请填写本次修正说明");
        }
        String submittedActualDate = (String) body.get("actualDate");
        task.setStatus("delivered");
        task.setActualDate(null);
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(validateAndCleanFiles(
                (String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(validateAndCleanFiles(
                (String) body.getOrDefault("attachmentsJson", "[]"), false));
        Integer selfScore = body.containsKey("selfScore") ? ((Number) body.get("selfScore")).intValue() : null;
        if (selfScore == null || selfScore < 1 || selfScore > 100) {
            throw new RuntimeException("请输入有效的自评分（1-100分）");
        }
        task.setSelfScore(selfScore.doubleValue());
        task.setReviewComments(null);
        resetReviewWorkflow(task);
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        saveDeliveryVersion(task, "correction", changeSummary, submittedActualDate, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务主动修正交付：" + task.getName()
                + "（" + changeSummary + "）", currentUser, currentRole, p));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_REDELIVERED", p.getPlannerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, changeSummary));
        if ("channel_custom".equals(p.getType()) && p.getSalesId() != null && !p.getSalesId().isBlank()
                && !p.getSalesId().equals(currentUserId)) {
            safeNotifyAfterCommit("TASK_REDELIVERED", p.getSalesId(), "sub_task", task.getId(), currentUserId,
                    notificationContext(p, task, currentUser, "销售关联项目已收到重新交付成果"));
        }
        return saved;
    }

    private void saveDeliveryVersion(SubTask task, String submissionType, String changeSummary,
                                     String submittedActualDate, String userId, String userName, String role) {
        SubTaskDeliveryVersion version = new SubTaskDeliveryVersion();
        version.setSubTask(task);
        version.setVersionNo(deliveryVersionRepository.findMaxVersionNoBySubTaskId(task.getId()) + 1);
        version.setSubmissionType(submissionType);
        version.setChangeSummary(changeSummary);
        version.setDeliverables(task.getDeliverables());
        version.setReferenceImagesJson(task.getReferenceImagesJson());
        version.setAttachmentsJson(task.getAttachmentsJson());
        version.setActualDate(submittedActualDate);
        version.setSelfScore(task.getSelfScore());
        version.setSubmittedById(userId);
        version.setSubmittedByName(userName);
        version.setSubmittedByRole(role);
        deliveryVersionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDeliveryVersions(Long taskId) {
        return deliveryVersionRepository.findBySubTaskIdOrderByVersionNoDesc(taskId).stream().map(version -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", version.getId());
            item.put("versionNo", version.getVersionNo());
            item.put("submissionType", version.getSubmissionType());
            item.put("changeSummary", version.getChangeSummary());
            item.put("deliverables", version.getDeliverables());
            item.put("referenceImagesJson", version.getReferenceImagesJson());
            item.put("attachmentsJson", version.getAttachmentsJson());
            item.put("actualDate", version.getActualDate());
            item.put("selfScore", version.getSelfScore());
            item.put("submittedByName", version.getSubmittedByName());
            item.put("submittedByRole", version.getSubmittedByRole());
            item.put("submittedAt", version.getSubmittedAt().toString());
            return item;
        }).toList();
    }

    public Project taskApprove(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        String comments = SecurityUtil.sanitizeText((String) body.getOrDefault("comments", ""), 500);
        boolean isChannel = "channel_custom".equals(p.getType());
        Integer score = parseOptionalScore(body.get("score"));

        if (!canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前用户无权验收该子任务");
        }

        // 防止按钮重复点击：首个请求已推进状态时，后续重复请求直接返回当前项目。
        boolean alreadyProcessed = ("planner".equals(currentRole) && "planner_approved".equals(task.getStatus()))
                || ("sales".equals(currentRole) && "sales_approved".equals(task.getStatus()))
                || ("admin".equals(currentRole) && "admin_approved".equals(task.getStatus()));
        if (alreadyProcessed) {
            return p;
        }

        if ("submitted_for_review".equals(task.getStatus()) && "planner".equals(currentRole)) {
            // Step 1: 企划验收 → 企划评分
            validateScoreRequired(score, "企划");
            task.setStatus("planner_approved");
            task.setReviewComments(comments);
            completeReviewRecord(task, "planner", currentUserId, currentUser, comments, score);
            p.getLogs().add(new ActivityLog("企划验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else if ("planner_approved".equals(task.getStatus()) && "sales".equals(currentRole) && isChannel) {
            // 渠道：销售验收 → 销售评分
            validateScoreRequired(score, "销售");
            task.setStatus("sales_approved");
            task.setReviewComments(comments);
            completeReviewRecord(task, "sales", currentUserId, currentUser, comments, score);
            p.getLogs().add(new ActivityLog("销售验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else if ("planner_approved".equals(task.getStatus()) && "admin".equals(currentRole) && !isChannel) {
            // 常规品：管理验收 → 管理评分
            validateScoreRequired(score, "管理员");
            task.setStatus("admin_approved");
            task.setReviewComments(comments);
            completeReviewRecord(task, "admin", currentUserId, currentUser, comments, score);
            p.getLogs().add(new ActivityLog("管理验收通过并评分：" + task.getName(), currentUser, currentRole, p));
        } else {
            throw new RuntimeException("当前状态无法执行验收操作");
        }

        // 检查是否所有评分已完成；最终验收通过时发放质量加分（与评分中心 submitScoring 共用同一发分路径）
        finalizeTaskApproval(task, p);
        Project saved = projectRepository.save(p);
        Map<String, String> notifyContext = notificationContext(saved, task, currentUser, comments);
        notifyContext.put("reviewRole", "planner".equals(currentRole) ? "产品企划" : ("admin".equals(currentRole) ? "管理员" : "销售"));
        safeNotifyAfterCommit("REVIEW_APPROVED", task.getDesignerId(), "sub_task", task.getId(),
                currentUserId, notifyContext);
        return saved;
    }

    /** 子任务交付或重新交付时，建立两级待审核记录。 */
    private void resetReviewWorkflow(SubTask task) {
        List<String> roles = expectedReviewRoles(task);
        for (int index = 0; index < roles.size(); index++) {
            String role = roles.get(index);
            ScoringRecord record = scoringRepository.findBySubTaskIdAndRole(task.getId(), role)
                    .orElseGet(ScoringRecord::new);
            record.setRole(role);
            record.setScoreType(role);
            record.setReviewStage(index == 0 ? "first" : "second");
            record.setReviewStatus(index == 0 ? "pending" : "waiting");
            record.setReviewerId(null);
            record.setReviewerName(null);
            record.setReviewedAt(null);
            record.setComment(null);
            record.setScore(null);
            record.setAesthetics(null);
            record.setInnovation(null);
            record.setWeight(getScoringPct(projectType(task), role) / 100.0);
            record.setSubTask(task);
            scoringRepository.save(record);
        }
        // 设计师自评分纳入统一评分链路，使用系统设置中的设计师权重参与综合分。
        if ("designer".equalsIgnoreCase(task.getAssigneeRole())) {
            ScoringRecord self = scoringRepository.findBySubTaskIdAndRole(task.getId(), "designer")
                    .orElseGet(ScoringRecord::new);
            self.setRole("designer");
            self.setScoreType("self");
            self.setReviewStage("self");
            self.setReviewStatus(task.getSelfScore() == null ? "pending" : "approved");
            self.setReviewerId(task.getDesignerId());
            self.setReviewerName(task.getDesignerName());
            self.setReviewedAt(task.getSelfScore() == null ? null : LocalDateTime.now());
            self.setScore(task.getSelfScore() == null ? null : task.getSelfScore().intValue());
            self.setWeight(getScoringPct(projectType(task), "designer") / 100.0);
            self.setSubTask(task);
            scoringRepository.save(self);
        }
    }

    /** 审核通过时更新对应阶段记录（单维度：总分100分）。 */
    private void completeReviewRecord(SubTask task, String role, String reviewerId,
                                      String reviewerName, String comment, Integer score) {
        ScoringRecord sr = scoringRepository.findBySubTaskIdAndRole(task.getId(), role)
                .orElseGet(ScoringRecord::new);
        sr.setRole(role);
        sr.setScoreType(role);
        sr.setReviewStage(reviewStage(task, role));
        sr.setReviewStatus("approved");
        sr.setReviewerId(reviewerId);
        sr.setReviewerName(reviewerName);
        sr.setReviewedAt(LocalDateTime.now());
        sr.setComment(comment);
        sr.setScore(score);
        // 兼容保留旧字段
        if (score != null) {
            // 将百分制映射到10分制作为兼容值
            double mapped = score / 10.0;
            sr.setAesthetics(mapped);
            sr.setInnovation(mapped);
        } else {
            sr.setAesthetics(null);
            sr.setInnovation(null);
        }
        // 读取对应项目类型的角色权重百分比，转为小数
        sr.setWeight(getScoringPct(projectType(task), role) / 100.0);
        sr.setSubTask(task);
        scoringRepository.save(sr);
        if ("planner".equals(role)) {
            activateSecondReview(task);
        }
    }

    private void activateSecondReview(SubTask task) {
        String secondRole = expectedReviewRoles(task).get(1);
        ScoringRecord secondReview = scoringRepository.findBySubTaskIdAndRole(task.getId(), secondRole)
                .orElseGet(ScoringRecord::new);
        boolean newlyActivated = !"pending".equals(secondReview.getReviewStatus());
        secondReview.setRole(secondRole);
        secondReview.setScoreType(secondRole);
        secondReview.setReviewStage("second");
        secondReview.setReviewStatus("pending");
        secondReview.setWeight(getScoringPct(projectType(task), secondRole) / 100.0);
        secondReview.setSubTask(task);
        scoringRepository.save(secondReview);
        if (newlyActivated && task.getProject() != null) {
            Project project = task.getProject();
            Map<String, String> context = notificationContext(project, task, "产品企划", null);
            context.put("reviewRole", "admin".equals(secondRole) ? "管理员" : "销售");
            if ("admin".equals(secondRole)) {
                safeNotifyRole("REVIEW_PENDING", "admin", "sub_task", task.getId(),
                        "system", context);
            } else if (project.getSalesId() != null && !project.getSalesId().isBlank()) {
                safeNotify("REVIEW_PENDING", project.getSalesId(), "sub_task", task.getId(),
                        "system", context);
            }
        }
    }

    private void safeNotifyRole(String eventType, String role, String aggregateType, Long aggregateId,
                                String actorUserId, Map<String, String> context) {
        try {
            notificationWorkflowService.notifyRole(eventType, role, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("角色通知创建失败但业务操作继续: eventType={}, role={}, aggregate={}#{}",
                    eventType, role, aggregateType, aggregateId, e);
        }
    }

    private List<String> expectedReviewRoles(SubTask task) {
        return "channel_custom".equals(projectType(task))
                ? List.of("planner", "sales")
                : List.of("planner", "admin");
    }

    private String reviewStage(SubTask task, String role) {
        return expectedReviewRoles(task).get(0).equals(role) ? "first" : "second";
    }

    private String projectType(SubTask task) {
        return task.getProject() != null && task.getProject().getType() != null
                ? task.getProject().getType() : "regular";
    }

    /** 从 SystemConfig 读取评分权重百分比，按项目类型+角色 */
    private double getScoringPct(String projectType, String role) {
        String key = "scoring." + projectType + "." + role;
        return systemConfigRepository.findByConfigKey(key)
            .map(c -> { try { return Double.parseDouble(c.getConfigValue()); } catch (Exception e) { return 25.0; } })
            .orElse(25.0);
    }

    /** 当前系统设置中的角色权重（小数形式），用于历史评分重新核算。 */
    public double currentScoringWeight(String projectType, String role) {
        return getScoringPct(projectType, role) / 100.0;
    }

    private Map<String, Double> scoringWeightMap(String projectType) {
        Map<String, Double> weights = new HashMap<>();
        for (String role : List.of("planner", "sales", "designer", "admin")) {
            weights.put(role, getScoringPct(projectType, role) / 100.0);
        }
        return weights;
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
                task.setActualDate(java.time.LocalDate.now().toString());
            }
        } else {
            // 常规品：企划评分 + 管理评分 → completed
            if ("admin_approved".equals(task.getStatus())) {
                task.setStatus("completed");
                task.setActualDate(java.time.LocalDate.now().toString());
            }
        }
        // 检查项目是否所有子任务都已完成
        if ("completed".equals(task.getStatus())) {
            boolean allDone = project.getTasks().stream().allMatch(t -> "completed".equals(t.getStatus()));
            boolean bulkStageDone = project.getTasks().stream()
                    .filter(t -> "bulk".equals(t.getWorkflowStage()))
                    .findAny()
                    .isPresent()
                    && project.getTasks().stream()
                    .filter(t -> "bulk".equals(t.getWorkflowStage()))
                    .allMatch(t -> "completed".equals(t.getStatus()));
            if (allDone && bulkStageDone) {
                project.setStatus("completed");
            }
        }
    }

    /**
     * 最终验收通过（任务进入 completed）时发放质量加分。
     * 任务详情入口（taskApprove）与评分中心入口（submitScoring）共用本方法，
     * 保证同一业务动作「最终验收通过」在两个入口的积分结果一致；
     * awardQualityCompletion 内部以用户+子任务+规则码幂等，同一任务只会发放一次。
     */
    private void finalizeTaskApproval(SubTask task, Project project) {
        checkTaskCompletion(task, project);
        if ("completed".equals(task.getStatus()) && pointsService != null) {
            pointsService.awardQualityCompletion(task);
        }
    }

    public Project taskReject(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String comments = SecurityUtil.sanitizeText((String) body.get("comments"), 500);
        String requiredCompletionDate = SecurityUtil.sanitizeText((String) body.get("requiredCompletionDate"), 20);
        if (requiredCompletionDate == null || !requiredCompletionDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new RuntimeException("请选择有效的要求完成时间");
        }
        String rejectionReferenceImagesJson = validateAndCleanFiles(
                (String) body.getOrDefault("rejectionReferenceImagesJson", "[]"), true);
        String rejectionAttachmentsJson = validateAndCleanFiles(
                (String) body.getOrDefault("rejectionAttachmentsJson", "[]"), false);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        boolean canReject = ("planner".equals(currentRole) && List.of("delivered", "submitted_for_review").contains(task.getStatus()))
                || ("sales".equals(currentRole) && "channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()))
                || ("admin".equals(currentRole) && !"channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()));
        if (!canReject || !canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前角色或任务状态无法驳回");
        }
        rejectReviewRecord(task, currentRole, currentUserId, currentUser, comments);
        task.setStatus("rejected");
        task.setReviewComments(comments);
        task.setPlannedDate(requiredCompletionDate);
        Map<String, Object> submittedSnapshot = new LinkedHashMap<>();
        submittedSnapshot.put("deliverables", task.getDeliverables());
        submittedSnapshot.put("referenceImagesJson", task.getReferenceImagesJson());
        submittedSnapshot.put("attachmentsJson", task.getAttachmentsJson());
        submittedSnapshot.put("actualDate", task.getActualDate());
        submittedSnapshot.put("submittedById", task.getDesignerId());
        submittedSnapshot.put("submittedByName", task.getDesignerName());
        Map<String, Object> rejectionSnapshot = new LinkedHashMap<>();
        rejectionSnapshot.put("reason", comments == null ? "" : comments);
        rejectionSnapshot.put("requiredCompletionDate", requiredCompletionDate);
        rejectionSnapshot.put("rejectionReferenceImagesJson", rejectionReferenceImagesJson);
        rejectionSnapshot.put("rejectionAttachmentsJson", rejectionAttachmentsJson);
        p.getLogs().add(new ActivityLog(
                "子任务驳回：" + task.getName() + "（意见：" + comments + "）",
                currentUser, currentRole, p, "sub_task", task.getId(),
                toJson(submittedSnapshot),
                toJson(rejectionSnapshot),
                "status,reviewComments,plannedDate,rejectionReferenceImagesJson,rejectionAttachmentsJson"));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(rejectionReferenceImagesJson, "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(rejectionAttachmentsJson, "sub_task", task.getId());
        safeNotifyAfterCommit("TASK_REJECTED", task.getDesignerId(), "sub_task", task.getId(), currentUserId,
                notificationContext(p, task, currentUser, comments));
        return saved;
    }

    private void rejectReviewRecord(SubTask task, String role, String reviewerId,
                                    String reviewerName, String comment) {
        ScoringRecord record = scoringRepository.findBySubTaskIdAndRole(task.getId(), role)
                .orElseGet(ScoringRecord::new);
        record.setRole(role);
        record.setScoreType(role);
        record.setReviewStage(reviewStage(task, role));
        record.setReviewStatus("rejected");
        record.setReviewerId(reviewerId);
        record.setReviewerName(reviewerName);
        record.setReviewedAt(LocalDateTime.now());
        record.setComment(comment);
        record.setScore(null);
        record.setAesthetics(null);
        record.setInnovation(null);
        record.setWeight(getScoringPct(projectType(task), role) / 100.0);
        record.setSubTask(task);
        scoringRepository.save(record);
    }

    // ==================== Scoring ====================

    public Project submitScoring(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = lockProject(projectId);

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        SubTask task = lockSubTask(projectId, taskId);

        String role = (String) body.get("role");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (!List.of("planner", "sales", "admin").contains(role)
                || !role.equals(currentRole)
                || ("sales".equals(role) && !"channel_custom".equals(p.getType()))
                || ("admin".equals(role) && "channel_custom".equals(p.getType()))) {
            throw new RuntimeException("当前角色不能提交该项目评分");
        }
        if (!canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前用户无权提交该项目评分");
        }
        boolean validStage = ("planner".equals(role) && "submitted_for_review".equals(task.getStatus()))
                || ("sales".equals(role) && "channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()))
                || ("admin".equals(role) && !"channel_custom".equals(p.getType())
                    && "planner_approved".equals(task.getStatus()));
        if (!validStage) {
            throw new RuntimeException("当前审核阶段不能提交该评分");
        }
        Integer score = parseOptionalScore(body.get("score"));
        validateScoreRequired(score, "评分");

        ScoringRecord sr = scoringRepository.findBySubTaskIdAndRole(taskId, role)
                .orElseGet(() -> {
                    ScoringRecord newSr = new ScoringRecord();
                    newSr.setRole(role);
                    newSr.setSubTask(task);
                    newSr.setWeight(getScoringPct(task.getProject() != null ? task.getProject().getType() : "regular", role) / 100.0);
                    return newSr;
                });
        sr.setScore(score);
        double mapped = score / 10.0;
        sr.setAesthetics(mapped);
        sr.setInnovation(mapped);
        sr.setScoreType(role);
        sr.setReviewStage(reviewStage(task, role));
        sr.setReviewStatus("approved");
        sr.setReviewerId(currentUserId);
        sr.setReviewerName((String) body.getOrDefault("currentUser", ""));
        sr.setReviewedAt(LocalDateTime.now());
        if (body.containsKey("comment")) {
            sr.setComment(SecurityUtil.sanitizeText((String) body.get("comment"), 500));
        }
        scoringRepository.save(sr);

        if ("planner".equals(role)) {
            task.setStatus("planner_approved");
            activateSecondReview(task);
        } else if ("sales".equals(role)) {
            task.setStatus("sales_approved");
        } else {
            task.setStatus("admin_approved");
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog("子任务评分（" + role + "：" + score + "分）：" + task.getName(), currentUser, currentRole, p));

        // 最终验收通过时发放质量加分（与任务详情 taskApprove 共用同一发分路径）
        finalizeTaskApproval(task, p);
        return projectRepository.save(p);
    }


    private boolean isProjectPlanner(Project project, Map<String, Object> body) {
        String userId = (String) body.getOrDefault("currentUserId", "");
        return userId != null && !userId.isBlank() && Objects.equals(userId, project.getPlannerId());
    }

    /** 验收和评分必须同时满足角色、项目归属，不能仅由请求体中的 role 决定。 */
    private boolean canReviewTask(Project project, SubTask task, String role, String userId) {
        if (project == null || task == null || userId == null || userId.isBlank()) return false;
        if ("admin".equals(role)) return true;
        if ("planner".equals(role)) {
            return Objects.equals(userId, project.getPlannerId());
        }
        return "sales".equals(role)
                && "channel_custom".equals(project.getType())
                && Objects.equals(userId, project.getSalesId());
    }

    private Integer parseOptionalScore(Object rawScore) {
        if (rawScore == null) {
            return null;
        }
        Integer score;
        if (rawScore instanceof Number number) {
            score = number.intValue();
        } else {
            try {
                score = Integer.parseInt(String.valueOf(rawScore));
            } catch (NumberFormatException e) {
                throw new RuntimeException("评分必须为数字");
            }
        }
        if (score < 1 || score > 100) {
            throw new RuntimeException("评分必须在 1-100 之间");
        }
        return score;
    }

    private void validateScoreRequired(Integer score, String actorLabel) {
        if (score == null) {
            throw new RuntimeException(actorLabel + "评分不能为空");
        }
    }

    private List<String> getRequiredScoringRoles(String projectType) {
        return "channel_custom".equals(projectType)
                ? List.of("planner", "sales")
                : List.of("planner", "admin");
    }

    private List<String> getRequiredScoringRoles(SubTask task) {
        List<String> roles = new ArrayList<>(getRequiredScoringRoles(
                task.getProject() != null ? task.getProject().getType() : "regular"));
        if ("designer".equalsIgnoreCase(task.getAssigneeRole()) && task.getSelfScore() != null) {
            roles.add("designer");
        }
        return roles;
    }

    private boolean isScoringRecordCompleted(ScoringRecord record) {
        if (record.getReviewStatus() != null) {
            return "approved".equals(record.getReviewStatus());
        }
        return record.getScore() != null
                || (record.getAesthetics() != null && record.getInnovation() != null);
    }

    private boolean isScoringRecordPending(ScoringRecord record) {
        if (record.getReviewStatus() != null) {
            return "pending".equals(record.getReviewStatus());
        }
        return !isScoringRecordCompleted(record);
    }

    private Double toHundredPointScore(ScoringRecord record) {
        if (record.getScore() != null) {
            return record.getScore().doubleValue();
        }
        if (record.getAesthetics() != null && record.getInnovation() != null) {
            return ((record.getAesthetics() + record.getInnovation()) / 2.0) * 10.0;
        }
        return null;
    }

}
