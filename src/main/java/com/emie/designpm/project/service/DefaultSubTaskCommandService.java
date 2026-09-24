package com.emie.designpm.project.service;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.service.PermissionCatalog;
import com.emie.designpm.admin.service.UserService;
import com.emie.designpm.entity.*;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.materialmarket.repository.DesignerMarketEligibilityRepository;
import com.emie.designpm.notification.repository.NotificationRepository;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.repository.PointAppealRepository;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.service.PointsService;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskDeliveryVersionRepository;
import com.emie.designpm.project.repository.SubTaskRejectionCycleRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.repository.TaskWithdrawalRepository;
import com.emie.designpm.reference.repository.IpOptionRepository;
import com.emie.designpm.reference.repository.ProductCategoryRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.sync.service.SyncQueueService;
import com.emie.designpm.util.SecurityUtil;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DefaultSubTaskCommandService implements SubTaskCommandService {
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
    private final SubTaskInputValidator inputValidator;
    private PointsService pointsService;
    private DesignerMarketEligibilityRepository marketEligibilityRepository;
    private TaskWithdrawalRepository taskWithdrawalRepository;
    private PointAdjustmentLedgerRepository pointAdjustmentLedgerRepository;
    private PointLedgerRepository pointLedgerRepository;
    private PointAppealRepository pointAppealRepository;
    private NotificationRepository notificationRepository;
    private FileRecordRepository fileRecordRepository;
    private SubTaskRejectionCycleRepository rejectionCycleRepository;
    private final ProjectNotifier notifier;
    private final ScoringWeightConfig scoringWeights;

    @Autowired
    public DefaultSubTaskCommandService(
            ProjectRepository projectRepository,
            SubTaskRepository subTaskRepository,
            ScoringRepository scoringRepository,
            SubTaskDeliveryVersionRepository deliveryVersionRepository,
            UserService userService,
            ProductCategoryRepository productCategoryRepository,
            IpOptionRepository ipOptionRepository,
            SystemConfigRepository systemConfigRepository,
            SyncQueueService syncQueueService,
            FileArchiveService fileArchiveService,
            ProjectAccessService projectAccessService,
            NotificationWorkflowService notificationWorkflowService,
            SubTaskInputValidator inputValidator) {
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
        this.inputValidator = inputValidator;
        this.notifier = new ProjectNotifier(notificationWorkflowService);
        this.scoringWeights = new ScoringWeightConfig(systemConfigRepository);
    }

    /** Keeps existing lightweight unit-test construction compatible. */
    public DefaultSubTaskCommandService(
            ProjectRepository projectRepository,
            SubTaskRepository subTaskRepository,
            ScoringRepository scoringRepository,
            SubTaskDeliveryVersionRepository deliveryVersionRepository,
            UserService userService,
            ProductCategoryRepository productCategoryRepository,
            IpOptionRepository ipOptionRepository,
            SystemConfigRepository systemConfigRepository,
            SyncQueueService syncQueueService,
            FileArchiveService fileArchiveService,
            ProjectAccessService projectAccessService,
            NotificationWorkflowService notificationWorkflowService) {
        this(
                projectRepository,
                subTaskRepository,
                scoringRepository,
                deliveryVersionRepository,
                userService,
                productCategoryRepository,
                ipOptionRepository,
                systemConfigRepository,
                syncQueueService,
                fileArchiveService,
                projectAccessService,
                notificationWorkflowService,
                new SubTaskInputValidator(userService, subTaskRepository, systemConfigRepository));
    }

    /** Optional setter keeps existing lightweight unit-test construction compatible. */
    @Autowired(required = false)
    void setPointsService(PointsService pointsService) {
        this.pointsService = pointsService;
    }

    @Autowired(required = false)
    void setMarketEligibilityRepository(DesignerMarketEligibilityRepository repository) {
        this.marketEligibilityRepository = repository;
        this.inputValidator.setMarketEligibilityRepository(repository);
    }

    @Autowired(required = false)
    void setTaskWithdrawalRepository(TaskWithdrawalRepository repository) {
        this.taskWithdrawalRepository = repository;
    }

    @Autowired(required = false)
    void setPointAdjustmentLedgerRepository(PointAdjustmentLedgerRepository repository) {
        this.pointAdjustmentLedgerRepository = repository;
    }

    @Autowired(required = false)
    void setPointLedgerRepository(PointLedgerRepository repository) {
        this.pointLedgerRepository = repository;
    }

    @Autowired(required = false)
    void setPointAppealRepository(PointAppealRepository repository) {
        this.pointAppealRepository = repository;
    }

    @Autowired(required = false)
    void setNotificationRepository(NotificationRepository repository) {
        this.notificationRepository = repository;
    }

    @Autowired(required = false)
    void setFileRecordRepository(FileRecordRepository repository) {
        this.fileRecordRepository = repository;
    }

    @Autowired
    void setRejectionCycleRepository(SubTaskRejectionCycleRepository repository) {
        this.rejectionCycleRepository = repository;
    }

    public Project addSubTask(Long projectId, Map<String, Object> body) {
        Project p = projectRepository.findById(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));

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
        if (pointRuleCode == null || pointRuleCode.isBlank()) {
            throw new IllegalArgumentException("请选择积分规则");
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
            pointsService.bindRuleSnapshot(task, pointRuleCode);
        }
        task.setRequiredSkillTagsJson(inputValidator.skillTags(body.get("requiredSkillTags")));
        task.setCollaboratorAllocationsJson(
                inputValidator.collaboratorAllocations(body.get("collaboratorAllocations"), designerId));
        task.setMilestoneMonth(inputValidator.milestoneMonth(body.get("milestoneMonth")));
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
        inputValidator.validateSubTaskAssignee(designerId, task.getAssigneeRole());
        // 设计师不再按任务分类或能力标签限制；所有设计师均可承接设计师类子任务。
        task.setDetails(details);
        task.setReferenceImagesJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
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
            p.setCompletedAt(null);
        }

        String currentUser = (String) body.getOrDefault("currentUser", "");
        p.getLogs().add(new ActivityLog((publishToMarket ? "发布接单市场子任务：" : "添加子任务：") + name, currentUser, role, p));

        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        // 统一按负责人 ID 通知，designerId 兼容设计、供应链、销售、产品推广等负责人类型。
        if (task.getDesignerId() != null && !task.getDesignerId().isBlank()) {
            notifier.safeNotifyAfterCommit(
                    "TASK_ASSIGNED",
                    task.getDesignerId(),
                    "sub_task",
                    task.getId(),
                    (String) body.getOrDefault("currentUserId", ""),
                    notifier.context(saved, task, currentUser, ""));
        }
        return saved;
    }

    public Project updateSubTask(Long projectId, Long taskId, Map<String, Object> body) {
        // 与抢单、撤回保持相同的 project -> subtask 锁序，避免编辑覆盖并发抢单结果。
        Project p = projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));

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

        SubTask task = subTaskRepository.findByIdForUpdate(taskId).orElseThrow(() -> new RuntimeException("子任务不存在"));
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
            task.setAssigneeRole(
                    assigneeRole != null
                                    && !assigneeRole.isBlank()
                                    && !"designer".equals(PermissionCatalog.normalizeRole(assigneeRole))
                            ? assigneeRole
                            : "designer");
        }
        if (body.containsKey("pointRuleCode")) {
            if (!"pending".equals(task.getStatus())) {
                throw new RuntimeException("子任务开始执行后不能修改积分规则");
            }
            if (pointsService == null) throw new RuntimeException("积分规则服务暂不可用，请稍后重试");
            String ruleCode =
                    body.containsKey("pointRuleCode") ? (String) body.get("pointRuleCode") : task.getPointRuleCode();
            if (ruleCode == null || ruleCode.isBlank()) {
                task.setPointRuleCode(null);
                task.setDifficultyMultiplierSnapshot(null);
                task.setBasePointSnapshot(null);
                task.setDifficultyMultiplierSnapshot(null);
                task.setQualityBonusThresholdSnapshot(null);
                task.setQualityBonusRatioSnapshot(null);
                task.setQualityTopThresholdSnapshot(null);
                task.setQualityTopRatioSnapshot(null);
                task.setMaxTotalMultiplierSnapshot(null);
                task.setCountInPerformanceSnapshot(null);
            } else {
                pointsService.bindRuleSnapshot(task, ruleCode);
            }
        }
        if (body.containsKey("requiredSkillTags")) {
            if (!"pending".equals(task.getStatus())) {
                throw new RuntimeException("子任务开始执行后不能修改能力要求");
            }
            task.setRequiredSkillTagsJson(inputValidator.skillTags(body.get("requiredSkillTags")));
        }
        if (body.containsKey("collaboratorAllocations") || body.containsKey("milestoneMonth")) {
            if (!"pending".equals(task.getStatus())) throw new RuntimeException("任务开始后不能修改合作比例或里程碑月份");
            if (body.containsKey("collaboratorAllocations")) {
                task.setCollaboratorAllocationsJson(inputValidator.collaboratorAllocations(
                        body.get("collaboratorAllocations"), task.getDesignerId()));
            }
            if (body.containsKey("milestoneMonth"))
                task.setMilestoneMonth(inputValidator.milestoneMonth(body.get("milestoneMonth")));
        }
        if (body.containsKey("assignmentReason"))
            task.setAssignmentReason(SecurityUtil.sanitizeText((String) body.get("assignmentReason"), 500));
        if ("market_open".equals(task.getAllocationStatus())
                && (!"designer".equals(task.getAssigneeRole())
                        || (task.getDesignerId() != null
                                && !task.getDesignerId().isBlank()))) {
            throw new RuntimeException("开放市场任务必须保持设计师类型且不能指定负责人");
        }
        inputValidator.validateSubTaskAssignee(
                task.getDesignerId(), task.getAssigneeRole() == null ? "designer" : task.getAssigneeRole());
        if (body.containsKey("details")) task.setDetails(SecurityUtil.sanitizeText((String) body.get("details"), 2000));
        if (body.containsKey("referenceImagesJson"))
            task.setReferenceImagesJson(
                    inputValidator.validateAndCleanFiles((String) body.get("referenceImagesJson"), true));
        if (body.containsKey("attachmentsJson"))
            task.setAttachmentsJson(inputValidator.validateAndCleanFiles((String) body.get("attachmentsJson"), false));

        String currentUser = (String) body.getOrDefault("currentUser", "");
        Map<String, Object> after = snapshotSubTask(task);
        p.getLogs()
                .add(new ActivityLog(
                        "编辑子任务：" + task.getName(),
                        currentUser,
                        currentRole,
                        p,
                        "sub_task",
                        task.getId(),
                        AuditJson.toJson(before),
                        AuditJson.toJson(after),
                        AuditJson.changedFields(before, after)));

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
        List<TaskWithdrawal> withdrawals = taskWithdrawalRepository == null
                ? List.of()
                : taskWithdrawalRepository.findBySubTaskIdIn(List.of(taskId));
        List<Long> withdrawalIds =
                withdrawals.stream().map(TaskWithdrawal::getId).toList();
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
        return projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));
    }

    /** All task workflow mutations must acquire locks in project -> subtask order. */
    private SubTask lockSubTask(Long projectId, Long taskId) {
        SubTask task = subTaskRepository.findByIdForUpdate(taskId).orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }
        return task;
    }

    @Transactional
    public Project taskAccept(Long projectId, Long taskId, Map<String, Object> body) {
        // 锁定项目行
        Project p = projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));

        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("项目已" + ("terminated".equals(p.getStatus()) ? "终止" : "暂停") + "，无法操作");
        }

        // 锁定子任务行
        SubTask task = subTaskRepository.findByIdForUpdate(taskId).orElseThrow(() -> new RuntimeException("子任务不存在"));

        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId)) {
            throw new RuntimeException("子任务不属于当前项目");
        }

        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String designerUserId = (String) body.get("designerUserId");

        if (designerUserId == null || designerUserId.isBlank()) {
            throw new RuntimeException("当前登录用户无效，无法接单");
        }
        if (task.getAssigneeRole() != null
                && !task.getAssigneeRole().isBlank()
                && !inputValidator
                        .normalizeAssigneeRole(task.getAssigneeRole())
                        .equals(inputValidator.normalizeAssigneeRole(currentRole))) {
            throw new RuntimeException("当前角色无法接此子任务");
        }

        // 子任务已被他人接单
        if (!"pending".equals(task.getStatus())) {
            throw new RuntimeException("该子任务已被接单或已处理");
        }

        if (task.getDesignerId() == null || task.getDesignerId().isBlank()) {
            inputValidator.validateMarketClaimConstraints(task, designerUserId);
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
                p.getLogs()
                        .add(new ActivityLog(
                                "设计师接单：" + task.getName() + "（自动绑定" + task.getDesignerName() + "）",
                                currentUser,
                                currentRole,
                                p));
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
        notifier.safeNotifyAfterCommit(
                "TASK_ASSIGNED",
                task.getDesignerId(),
                "sub_task",
                task.getId(),
                (String) body.getOrDefault("currentUserId", ""),
                notifier.context(saved, task, currentUser, ""));
        return saved;
    }

    private int positiveIntConfig(String key, int fallback) {
        return systemConfigRepository
                .findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                })
                .filter(value -> value > 0)
                .orElse(fallback);
    }

    @Transactional
    public Project withdrawMarketTask(Long projectId, Long taskId, Map<String, Object> body) {
        Project p = projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));
        String role = (String) body.getOrDefault("currentRole", "");
        if (!List.of("planner", "admin").contains(role)) throw new RuntimeException("仅企划或管理员可撤回市场任务");
        if ("planner".equals(role) && !isProjectPlanner(p, body)) throw new RuntimeException("仅项目负责人企划可撤回市场任务");
        SubTask task = subTaskRepository.findByIdForUpdate(taskId).orElseThrow(() -> new RuntimeException("子任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId))
            throw new RuntimeException("子任务不属于当前项目");
        if (!"market_open".equals(task.getAllocationStatus()) || !"pending".equals(task.getStatus())) {
            throw new RuntimeException("该任务已被领取或不在接单市场");
        }
        task.setAllocationStatus("withdrawn");
        p.getLogs()
                .add(new ActivityLog(
                        "撤回接单市场子任务：" + task.getName(), (String) body.getOrDefault("currentUser", ""), role, p));
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
        DesignerMarketEligibility eligibility = !"designer".equals(task.getAssigneeRole())
                        || marketEligibilityRepository == null
                ? null
                : marketEligibilityRepository.findByUserIdForUpdate(userId).orElseGet(() -> {
                    DesignerMarketEligibility x = new DesignerMarketEligibility();
                    x.setUserId(userId);
                    return x;
                });
        int previous = eligibility == null
                ? 0
                : Optional.ofNullable(eligibility.getViolationCount()).orElse(0);
        long freeMinutes = positiveLongConfig("points.withdrawal.free_minutes", 60);
        int suspendCount = positiveIntConfig("points.withdrawal.suspend_count", 3);
        double perWithdrawalRate = boundedDoubleConfig("points.withdrawal.penalty_rate", 10d) / 100d;
        int suspendDays = positiveIntConfig("points.withdrawal.suspend_days", 7);
        double ratio = elapsed <= freeMinutes ? 0d : Math.min(1d, perWithdrawalRate * (previous + 1));
        int base = (int) Math.round(Optional.ofNullable(task.getBasePointSnapshot())
                        .orElse(0)
                * Optional.ofNullable(task.getDifficultyMultiplierSnapshot()).orElse(1d));
        int penalty = (int) Math.ceil(base * ratio);
        // 积分仅面向设计师任务：供应链等其它负责人类型的退单不扣分（P2-3），事件 penaltyPoints=0、reason 走免罚文案。
        if (!"designer".equals(task.getAssigneeRole())) penalty = 0;
        TaskWithdrawal event = new TaskWithdrawal();
        event.setSubTaskId(taskId);
        event.setUserId(userId);
        event.setElapsedMinutes(elapsed);
        event.setPenaltyRatio(ratio);
        event.setPenaltyPoints(penalty);
        event.setReason(penalty <= 0 ? "接单1小时内退单（免罚）" : "接单超1小时退单，按累计次数比例扣分");
        taskWithdrawalRepository.save(event);
        // 积分仅面向设计师任务：penalty 已对非设计师置 0，仅设计师任务可能产生积分扣减（调账），退单一律记录。
        if (penalty > 0 && pointAdjustmentLedgerRepository != null) {
            PointAdjustmentLedger adjustment = new PointAdjustmentLedger();
            adjustment.setUserId(userId);
            adjustment.setSourceType("TASK_WITHDRAWAL");
            adjustment.setSourceId(event.getId());
            adjustment.setPoints(-penalty);
            adjustment.setReason(event.getReason());
            adjustment.setCreatedBy(userId);
            pointAdjustmentLedgerRepository.save(adjustment);
        }
        if (eligibility != null) {
            eligibility.setViolationCount(previous + 1);
            eligibility.setReason("退单累计" + (previous + 1) + "次");
            if (previous + 1 >= suspendCount) eligibility.setSuspendedUntil(now.plusDays(suspendDays));
            eligibility.setUpdatedBy(userId);
            marketEligibilityRepository.save(eligibility);
        }
        task.setDesignerId(null);
        task.setDesignerName(null);
        task.setClaimedAt(null);
        task.setStatus("pending");
        task.setAllocationStatus("market_open");
        p.getLogs()
                .add(new ActivityLog(
                        "设计师退单：" + task.getName() + "，扣分" + penalty,
                        String.valueOf(body.getOrDefault("currentUser", userId)),
                        "designer",
                        p));
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
        p.getLogs()
                .add(new ActivityLog(
                        "企划取消接单：" + task.getName(), String.valueOf(body.getOrDefault("currentUser", "")), role, p));
        return projectRepository.saveAndFlush(p);
    }

    private long positiveLongConfig(String key, long fallback) {
        try {
            return Math.max(
                    0,
                    Long.parseLong(systemConfigRepository
                            .findByConfigKey(key)
                            .map(SystemConfig::getConfigValue)
                            .orElse(String.valueOf(fallback))
                            .trim()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private double boundedDoubleConfig(String key, double fallback) {
        try {
            return Math.min(
                    100d,
                    Math.max(
                            0d,
                            Double.parseDouble(systemConfigRepository
                                    .findByConfigKey(key)
                                    .map(SystemConfig::getConfigValue)
                                    .orElse(String.valueOf(fallback))
                                    .trim())));
        } catch (Exception e) {
            return fallback;
        }
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
        task.setStatus("submitted_for_review");
        task.setActualDate(null);
        task.setSubmittedForReviewAt(LocalDateTime.now());
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        task.setSelfScore(null);
        resetReviewWorkflow(task);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        saveDeliveryVersion(task, "initial", "首次交付", submittedActualDate, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务交付：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.saveAndFlush(p);
        if (pointsService != null) pointsService.awardBaseSubmission(task);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        notifier.safeNotifyAfterCommit(
                "TASK_DELIVERED",
                p.getPlannerId(),
                "sub_task",
                task.getId(),
                currentUserId,
                notifier.context(p, task, currentUser, ""));
        if ("channel_custom".equals(p.getType())
                && p.getSalesId() != null
                && !p.getSalesId().isBlank()
                && !p.getSalesId().equals(currentUserId)) {
            notifier.safeNotifyAfterCommit(
                    "TASK_DELIVERED",
                    p.getSalesId(),
                    "sub_task",
                    task.getId(),
                    currentUserId,
                    notifier.context(p, task, currentUser, "销售关联项目已收到设计交付成果"));
        }
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
        task.setStatus("submitted_for_review");
        task.setActualDate(null);
        task.setSubmittedForReviewAt(LocalDateTime.now());
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        String previousReviewComments = task.getReviewComments();
        task.setReviewComments(null);
        task.setSubmittedForReviewAt(LocalDateTime.now());
        task.setSelfScore(null);

        // 重新交付后直接回到验收队列；操作日志继续保留历史过程。
        resetReviewWorkflow(task);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String changeSummary =
                SecurityUtil.sanitizeText((String) body.getOrDefault("changeSummary", "根据修改要求重新交付"), 500);
        saveDeliveryVersion(
                task, "redelivery", changeSummary, submittedActualDate, currentUserId, currentUser, currentRole);
        p.getLogs().add(new ActivityLog("子任务重新交付：" + task.getName(), currentUser, currentRole, p));

        Project saved = projectRepository.save(p);
        if (pointsService != null) pointsService.awardBaseSubmission(task);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        notifier.safeNotifyAfterCommit(
                "TASK_REDELIVERED",
                p.getPlannerId(),
                "sub_task",
                task.getId(),
                currentUserId,
                notifier.context(p, task, currentUser, previousReviewComments));
        if ("channel_custom".equals(p.getType())
                && p.getSalesId() != null
                && !p.getSalesId().isBlank()) {
            notifier.safeNotifyAfterCommit(
                    "TASK_REDELIVERED",
                    p.getSalesId(),
                    "sub_task",
                    task.getId(),
                    currentUserId,
                    notifier.context(p, task, currentUser, previousReviewComments));
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
        if (!"delivered".equals(task.getStatus())) {
            throw new RuntimeException("仅尚未进入验收的已交付任务可以更正");
        }
        String changeSummary = SecurityUtil.sanitizeText((String) body.get("changeSummary"), 500);
        if (changeSummary == null || changeSummary.isBlank()) {
            throw new RuntimeException("请填写本次修正说明");
        }
        String submittedActualDate = (String) body.get("actualDate");
        task.setStatus("submitted_for_review");
        task.setActualDate(null);
        task.setSubmittedForReviewAt(LocalDateTime.now());
        task.setDeliverables(SecurityUtil.sanitizeText((String) body.get("deliverables"), 5000));
        task.setReferenceImagesJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("referenceImagesJson", "[]"), true));
        task.setAttachmentsJson(
                inputValidator.validateAndCleanFiles((String) body.getOrDefault("attachmentsJson", "[]"), false));
        task.setSelfScore(null);
        task.setReviewComments(null);
        resetReviewWorkflow(task);
        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        saveDeliveryVersion(
                task, "correction", changeSummary, submittedActualDate, currentUserId, currentUser, currentRole);
        p.getLogs()
                .add(new ActivityLog(
                        "子任务主动修正交付：" + task.getName() + "（" + changeSummary + "）", currentUser, currentRole, p));
        Project saved = projectRepository.saveAndFlush(p);
        if (pointsService != null) pointsService.awardBaseSubmission(task);
        fileArchiveService.bindFilesFromJson(task.getReferenceImagesJson(), "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(task.getAttachmentsJson(), "sub_task", task.getId());
        notifier.safeNotifyAfterCommit(
                "TASK_CORRECTED",
                p.getPlannerId(),
                "sub_task",
                task.getId(),
                currentUserId,
                notifier.context(p, task, currentUser, changeSummary));
        return saved;
    }

    private void saveDeliveryVersion(
            SubTask task,
            String submissionType,
            String changeSummary,
            String submittedActualDate,
            String userId,
            String userName,
            String role) {
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
        return deliveryVersions(deliveryVersionRepository.findBySubTaskIdOrderByVersionNoDesc(taskId));
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Map<String, Object>>> getDeliveryVersionsByTaskIds(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return Map.of();
        return deliveryVersionRepository.findBySubTaskIdInOrderBySubTaskIdAscVersionNoDesc(taskIds).stream()
                .collect(Collectors.groupingBy(
                        version -> version.getSubTask().getId(),
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::deliveryVersions)));
    }

    private List<Map<String, Object>> deliveryVersions(List<SubTaskDeliveryVersion> versions) {
        return versions.stream()
                .map(version -> {
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
                })
                .toList();
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

        if (List.of("delivered", "submitted_for_review").contains(task.getStatus()) && "planner".equals(currentRole)) {
            task.setStatus("planner_approved");
            task.setReviewComments(comments);
            p.getLogs().add(new ActivityLog("产品企划验收通过：" + task.getName(), currentUser, currentRole, p));
            activateSecondReview(task);
        } else if ("planner_approved".equals(task.getStatus()) && "sales".equals(currentRole) && isChannel) {
            task.setStatus("sales_approved");
            task.setReviewComments(comments);
            p.getLogs().add(new ActivityLog("销售验收通过：" + task.getName(), currentUser, currentRole, p));
        } else {
            throw new RuntimeException("当前状态无法执行验收操作");
        }

        finalizeTaskApproval(task, p);
        Project saved = projectRepository.save(p);
        Map<String, String> notifyContext = notifier.context(saved, task, currentUser, comments);
        notifyContext.put(
                "reviewRole", "planner".equals(currentRole) ? "产品企划" : ("admin".equals(currentRole) ? "管理员" : "销售"));
        notifier.safeNotifyAfterCommit(
                "REVIEW_APPROVED", task.getDesignerId(), "sub_task", task.getId(), currentUserId, notifyContext);
        return saved;
    }

    /** 子任务验收不再创建评分记录。 */
    private void resetReviewWorkflow(SubTask task) {
        task.setSelfScore(null);
    }

    private void activateSecondReview(SubTask task) {
        Project project = task.getProject();
        if (project == null || !"channel_custom".equals(project.getType())) return;
        Map<String, String> context = notifier.context(project, task, "产品企划", null);
        context.put("reviewRole", "销售");
        if (project.getSalesId() != null && !project.getSalesId().isBlank()) {
            notifier.safeNotify("REVIEW_PENDING", project.getSalesId(), "sub_task", task.getId(), "system", context);
        }
    }

    private void safeNotifyRoleAfterCommit(
            String eventType,
            String role,
            String aggregateType,
            Long aggregateId,
            String actorUserId,
            Map<String, String> context) {
        try {
            notificationWorkflowService.notifyRoleAfterCommit(
                    eventType, role, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error(
                    "提交后角色通知注册失败但业务操作继续: eventType={}, role={}, aggregate={}#{}",
                    eventType,
                    role,
                    aggregateType,
                    aggregateId,
                    e);
        }
    }

    private List<String> expectedReviewRoles(SubTask task) {
        return "channel_custom".equals(projectType(task)) ? List.of("planner", "sales") : List.of("planner");
    }

    private String reviewStage(SubTask task, String role) {
        return expectedReviewRoles(task).get(0).equals(role) ? "first" : "second";
    }

    private String projectType(SubTask task) {
        return task.getProject() != null && task.getProject().getType() != null
                ? task.getProject().getType()
                : "regular";
    }

    /** 从 SystemConfig 读取评分权重百分比，按项目类型+角色 */
    /** 当前系统设置中的角色权重（小数形式），用于历史评分重新核算。 */
    public double currentScoringWeight(String projectType, String role) {
        return scoringWeights.pct(projectType, role) / 100.0;
    }

    /** 从 SystemConfig 读取评分权重，不存在则返回 1.0 */
    /** 检查子任务是否所有评分已完成 */
    private void checkTaskCompletion(SubTask task, Project project) {
        boolean isChannel = "channel_custom".equals(project.getType());
        if (isChannel) {
            // 渠道：企划评分 + 销售评分 → completed
            if ("sales_approved".equals(task.getStatus())) {
                task.setStatus("completed");
                task.setCompletedAt(java.time.LocalDateTime.now());
                task.setActualDate(java.time.LocalDate.now().toString());
            }
        } else if ("planner_approved".equals(task.getStatus())) {
            // 常规品由企划验收完成；管理员不再参与子任务二次验收。
            task.setStatus("completed");
            task.setCompletedAt(java.time.LocalDateTime.now());
            task.setActualDate(java.time.LocalDate.now().toString());
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
                project.setCompletedAt(java.time.LocalDateTime.now());
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
        String rejectionReferenceImagesJson = inputValidator.validateAndCleanFiles(
                (String) body.getOrDefault("rejectionReferenceImagesJson", "[]"), true);
        String rejectionAttachmentsJson = inputValidator.validateAndCleanFiles(
                (String) body.getOrDefault("rejectionAttachmentsJson", "[]"), false);

        String currentUser = (String) body.getOrDefault("currentUser", "");
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        boolean canReject = ("planner".equals(currentRole)
                        && List.of("delivered", "submitted_for_review").contains(task.getStatus()))
                || ("sales".equals(currentRole)
                        && "channel_custom".equals(p.getType())
                        && "planner_approved".equals(task.getStatus()))
                || ("admin".equals(currentRole)
                        && !"channel_custom".equals(p.getType())
                        && "planner_approved".equals(task.getStatus()));
        if (!canReject || !canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("当前角色或任务状态无法驳回");
        }
        SubTaskRejectionCycle latestCycle = rejectionCycleRepository
                .findFirstBySubTaskIdOrderBySequenceNoDesc(task.getId())
                .orElse(null);
        SubTaskRejectionCycle cycle = new SubTaskRejectionCycle();
        cycle.setSubTask(task);
        cycle.setSequenceNo(latestCycle == null ? 1 : latestCycle.getSequenceNo() + 1);
        cycle.setRejectionRole(currentRole);
        cycle.setRejectedById(currentUserId);
        cycle.setRejectedByName(currentUser);
        cycle.setRejectedAt(LocalDateTime.now());
        cycle.setPriorTaskStatus(task.getStatus());
        cycle.setPriorPlannedDate(task.getPlannedDate());
        cycle.setPriorReviewComments(task.getReviewComments());
        cycle.setPriorReviewSnapshotJson("{}");
        cycle.setReason(comments);
        cycle.setRequiredCompletionDate(requiredCompletionDate);
        cycle.setReferenceImagesJson(rejectionReferenceImagesJson);
        cycle.setAttachmentsJson(rejectionAttachmentsJson);
        rejectionCycleRepository.save(cycle);
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
        rejectionSnapshot.put("rejectionCycleId", cycle.getId());
        p.getLogs()
                .add(new ActivityLog(
                        "子任务驳回：" + task.getName() + "（意见：" + comments + "）",
                        currentUser,
                        currentRole,
                        p,
                        "sub_task",
                        task.getId(),
                        AuditJson.toJson(submittedSnapshot),
                        AuditJson.toJson(rejectionSnapshot),
                        "status,reviewComments,plannedDate,rejectionReferenceImagesJson,rejectionAttachmentsJson"));
        Project saved = projectRepository.saveAndFlush(p);
        fileArchiveService.bindFilesFromJson(rejectionReferenceImagesJson, "sub_task", task.getId());
        fileArchiveService.bindFilesFromJson(rejectionAttachmentsJson, "sub_task", task.getId());
        notifier.safeNotifyAfterCommit(
                "TASK_REJECTED",
                task.getDesignerId(),
                "sub_task",
                task.getId(),
                currentUserId,
                notifier.context(p, task, currentUser, comments));
        return saved;
    }

    public Project taskCancelReject(Long projectId, Long taskId, Long cycleId, Map<String, Object> body) {
        Project p = lockProject(projectId);
        if (List.of("terminated", "paused", "pending_terminate").contains(p.getStatus())) {
            throw new RuntimeException("当前项目状态不允许取消驳回");
        }
        SubTask task = lockSubTask(projectId, taskId);
        if (!"rejected".equals(task.getStatus())) {
            throw new RuntimeException("子任务已开始修改或重新交付，无法取消驳回");
        }
        SubTaskRejectionCycle cycle = rejectionCycleRepository
                .findById(cycleId)
                .orElseThrow(() -> new RuntimeException("驳回记录不存在或属于旧版本，无法取消"));
        SubTaskRejectionCycle latest = rejectionCycleRepository
                .findFirstBySubTaskIdAndStatusOrderBySequenceNoDesc(taskId, "ACTIVE")
                .orElseThrow(() -> new RuntimeException("当前没有可取消的驳回记录"));
        if (!Objects.equals(cycle.getSubTask().getId(), taskId)
                || !Objects.equals(latest.getId(), cycleId)
                || !"ACTIVE".equals(cycle.getStatus())) {
            throw new RuntimeException("只能取消当前生效的最近一次驳回");
        }
        String currentRole = (String) body.getOrDefault("currentRole", "");
        String currentUserId = (String) body.getOrDefault("currentUserId", "");
        if (!Objects.equals(currentRole, cycle.getRejectionRole())
                || !canReviewTask(p, task, currentRole, currentUserId)) {
            throw new RuntimeException("仅当前审核阶段的审核人可取消驳回");
        }

        task.setStatus(cycle.getPriorTaskStatus());
        task.setPlannedDate(cycle.getPriorPlannedDate());
        task.setReviewComments(cycle.getPriorReviewComments());

        String currentUser = (String) body.getOrDefault("currentUser", "");
        cycle.setStatus("CANCELLED");
        cycle.setCancelledById(currentUserId);
        cycle.setCancelledByName(currentUser);
        cycle.setCancelledByRole(currentRole);
        cycle.setCancelledAt(LocalDateTime.now());
        rejectionCycleRepository.save(cycle);
        p.getLogs()
                .add(new ActivityLog(
                        "取消子任务驳回：" + task.getName() + "（恢复至：" + cycle.getPriorTaskStatus() + "）",
                        currentUser,
                        currentRole,
                        p,
                        "sub_task",
                        task.getId(),
                        AuditJson.toJson(Map.of("status", "rejected", "rejectionCycleId", cycle.getId())),
                        AuditJson.toJson(
                                Map.of("status", cycle.getPriorTaskStatus(), "rejectionCycleId", cycle.getId())),
                        "status,plannedDate,reviewComments,scoringRecord"));
        return projectRepository.saveAndFlush(p);
    }

    // ==================== Scoring ====================

    public Project submitScoring(Long projectId, Long taskId, Map<String, Object> body) {
        throw new RuntimeException("子任务已改为验收流程，无需评分");
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
}
