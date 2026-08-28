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
public class DefaultProjectLifecycleCommandService implements ProjectLifecycleCommandService {
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
    private static final Logger log = LoggerFactory.getLogger(DefaultProjectLifecycleCommandService.class);
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

    public DefaultProjectLifecycleCommandService(ProjectRepository projectRepository,
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

    private Project lockProject(Long projectId) {
        return projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
    }

    /** All task workflow mutations must acquire locks in project -> subtask order. */
    public Project terminateProject(Long projectId, Map<String, Object> body) {
        Project p = lockProject(projectId);
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
        Project p = lockProject(projectId);
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
        Project p = lockProject(projectId);
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
        Project p = lockProject(projectId);
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

    /**
     * 删除项目并清理全部关联数据，避免遗留孤儿记录。
     * 现有表大多没有 FK 级联（baseline 为空迁移），必须显式清理，删除顺序满足 FK 依赖：
     * 调账(APPEAL/TASK_WITHDRAWAL) → 退单(FK→sub_tasks) → 异议(FK→point_ledgers) →
     * 积分流水 → 交付版本(FK→sub_tasks) → 评分(FK→sub_tasks) → 通知 → 文件记录 →
     * 项目本身（级联删除子任务与操作日志）。
     */
    public void deleteProject(Long projectId) {
        List<SubTask> tasks = subTaskRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        List<Long> taskIds = tasks.stream().map(SubTask::getId).toList();

        if (taskIds.isEmpty()) {
            scoringRepository.deleteByProjectId(projectId);
            projectRepository.deleteById(projectId);
            return;
        }

        // 1) 关联调账记录（异议调账按 appealId、退单调账按 withdrawalId）
        List<Long> withdrawalIds = taskWithdrawalRepository == null ? List.of()
                : taskWithdrawalRepository.findBySubTaskIdIn(taskIds).stream().map(TaskWithdrawal::getId).toList();
        List<Long> ledgerIds = pointLedgerRepository == null ? List.of()
                : pointLedgerRepository.findBySubTaskIdIn(taskIds).stream().map(PointLedger::getId).toList();
        List<Long> appealIds = (pointAppealRepository == null || ledgerIds.isEmpty()) ? List.of()
                : pointAppealRepository.findByPointLedgerIdIn(ledgerIds).stream().map(PointAppeal::getId).toList();
        if (pointAdjustmentLedgerRepository != null
                && (!appealIds.isEmpty() || !withdrawalIds.isEmpty())) {
            pointAdjustmentLedgerRepository.deleteProjectRelated(appealIds, withdrawalIds);
        }
        // 2) 退单记录（FK → sub_tasks，必须先删）
        if (taskWithdrawalRepository != null) taskWithdrawalRepository.deleteBySubTaskIds(taskIds);
        // 3) 积分异议（FK → point_ledgers，必须先删）
        if (pointAppealRepository != null && !ledgerIds.isEmpty()) pointAppealRepository.deleteByPointLedgerIds(ledgerIds);
        // 4) 积分流水
        if (pointLedgerRepository != null) pointLedgerRepository.deleteBySubTaskIds(taskIds);
        // 5) 交付版本（FK → sub_tasks）
        deliveryVersionRepository.deleteBySubTaskIds(taskIds);
        // 6) 评分记录（FK → sub_tasks）
        scoringRepository.deleteByProjectId(projectId);
        // 7) 通知（project / sub_task 聚合）
        if (notificationRepository != null) {
            notificationRepository.deleteByAggregateTypeAndAggregateIdIn("project", List.of(projectId));
            notificationRepository.deleteByAggregateTypeAndAggregateIdIn("sub_task", taskIds);
        }
        // 8) 文件记录（project / sub_task 目标）
        if (fileRecordRepository != null) {
            fileRecordRepository.deleteByTargetTypeAndTargetIdIn("project", List.of(projectId));
            fileRecordRepository.deleteByTargetTypeAndTargetIdIn("sub_task", taskIds);
        }
        // 9) 项目本身（级联删除子任务与操作日志；@PostRemove 在提交后入队飞书删除）
        projectRepository.deleteById(projectId);
    }

    // ==================== Role Status Board ====================

    /** 获取指定角色的状态看板（销售/企划/供应链/设计师） */
}
