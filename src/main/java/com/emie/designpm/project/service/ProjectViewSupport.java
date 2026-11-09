package com.emie.designpm.project.service;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.TaskDetailDTO;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskRejectionCycle;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.project.repository.SubTaskRejectionCycleRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 项目/子任务 → 响应视图的组装层：{@code toDetail}、评分明细、驳回记录、"我的子任务"聚合。
 * 只做读取与序列化，直接依赖 repository 属正常的 service 用法（不进 controller→repo 豁免清单）。
 * HTTP 请求上下文（session 解析、鉴权、body 补全）留在 {@code project.controller.ProjectRequestSupport}。
 */
@Service
public class ProjectViewSupport {

    static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ProjectService projectService;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;
    private final SubTaskRepository subTaskRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectWorkflowService projectWorkflowService;
    private final SubTaskCommandService subTaskCommandService;

    @Autowired(required = false)
    private PointLedgerRepository pointLedgerRepository;

    @Autowired(required = false)
    private SubTaskRejectionCycleRepository rejectionCycleRepository;

    public ProjectViewSupport(
            ProjectService projectService,
            ScoringRepository scoringRepository,
            ActivityLogRepository activityLogRepository,
            SubTaskRepository subTaskRepository,
            ProjectAccessService projectAccessService,
            ProjectWorkflowService projectWorkflowService,
            SubTaskCommandService subTaskCommandService) {
        this.projectService = projectService;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
        this.subTaskRepository = subTaskRepository;
        this.projectAccessService = projectAccessService;
        this.projectWorkflowService = projectWorkflowService;
        this.subTaskCommandService = subTaskCommandService;
    }

    public List<Map<String, Object>> getMySubTasks(AuthSession session) {
        List<SubTask> tasks = subTaskRepository.findMySubTasks(session.userId());
        List<Long> taskIds = tasks.stream().map(SubTask::getId).toList();
        Map<Long, List<Map<String, Object>>> scoringByTask = loadScoringDetails(taskIds);
        Map<Long, List<Map<String, Object>>> deliveriesByTask = Optional.ofNullable(
                        subTaskCommandService.getDeliveryVersionsByTaskIds(taskIds))
                .orElse(Map.of());
        Map<Long, List<PointLedger>> ledgersByTask = pointLedgerRepository == null
                ? Map.of()
                : pointLedgerRepository.findBySubTaskIdIn(taskIds).stream()
                        .collect(Collectors.groupingBy(PointLedger::getSubTaskId));
        Rejections rejections = loadRejections(taskIds, taskProjectIds(tasks));
        return tasks.stream()
                .map(task -> {
                    Project project = task.getProject();
                    var history = rejections.forTask(task, deliveriesByTask.getOrDefault(task.getId(), List.of()));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", task.getId());
                    item.put("name", task.getName());
                    item.put(
                            "createdAt",
                            task.getCreatedAt() == null
                                    ? null
                                    : task.getCreatedAt().format(DTF));
                    item.put("status", task.getStatus());
                    item.put("plannedDate", task.getPlannedDate());
                    item.put("actualDate", task.getActualDate());
                    item.put(
                            "submittedForReviewAt",
                            Optional.ofNullable(task.getSubmittedForReviewAt())
                                    .map(time -> time.format(DTF))
                                    .orElse(null));
                    item.put("designerId", task.getDesignerId());
                    item.put("designerName", task.getDesignerName());
                    item.put("allocationStatus", task.getAllocationStatus());
                    item.put("marketPublishedAt", task.getMarketPublishedAt());
                    item.put("claimedAt", task.getClaimedAt());
                    item.put("publisherId", task.getPublisherId());
                    item.put("publisherName", task.getPublisherName());
                    item.put("publisherRole", task.getPublisherRole());
                    item.put("assigneeRole", task.getAssigneeRole());
                    item.put("pointRuleCode", task.getPointRuleCode());
                    item.put("basePointSnapshot", task.getBasePointSnapshot());
                    item.put("supportsChangeTiers", task.isSupportsChangeTiers());
                    item.put("smallChangeNoteSnapshot", task.getSmallChangeNoteSnapshot());
                    item.put("largeChangeNoteSnapshot", task.getLargeChangeNoteSnapshot());
                    item.put("pendingChangeRuleCode", task.getPendingChangeRuleCode());
                    item.put("pendingChangeBonusRequestId", task.getPendingChangeBonusRequestId());
                    item.put("pendingChangeBonusPoints", task.getPendingChangeBonusPoints());
                    item.put("pendingChangeBonusReason", task.getPendingChangeBonusReason());
                    item.put("pendingChangeReferenceImagesJson", task.getPendingChangeReferenceImagesJson());
                    item.put("details", task.getDetails());
                    item.put("deliverables", task.getDeliverables());
                    item.put("referenceImagesJson", task.getReferenceImagesJson());
                    item.put("attachmentsJson", task.getAttachmentsJson());
                    item.put("reviewComments", task.getReviewComments());
                    item.put("selfScore", task.getSelfScore());
                    item.put("selfAesthetics", task.getSelfAesthetics());
                    item.put("selfInnovation", task.getSelfInnovation());
                    item.put("projectId", project.getId());
                    item.put("projectType", project.getType());
                    item.put("projectStatus", project.getStatus());
                    item.put("projectName", projectDisplayName(project));
                    item.put("plannerId", project.getPlannerId());
                    item.put("plannerName", project.getPlannerName());
                    item.put("salesId", project.getSalesId());
                    item.put("scoringRecords", scoringByTask.getOrDefault(task.getId(), List.of()));
                    item.put("rejectionRecords", history.rejections());
                    item.put("deliveryVersions", history.deliveries());
                    item.put("relation", session.userId().equals(task.getDesignerId()) ? "assignee" : "publisher");
                    List<PointLedger> ledgers = ledgersByTask.getOrDefault(task.getId(), List.of());
                    item.put("pointSummary", SubTaskPointView.summary(ledgers, history.deliveries()));
                    item.put(
                            "issuedPoints",
                            ledgers.stream()
                                    .map(PointLedger::getPoints)
                                    .filter(Objects::nonNull)
                                    .mapToDouble(Double::doubleValue)
                                    .sum());
                    item.put("issuedLedgerCount", ledgers.size());
                    return item;
                })
                .toList();
    }

    public List<Map<String, Object>> getDepartmentSubTasks(AuthSession session) {
        List<String> userIds = projectAccessService.departmentTaskUserIds(session.role(), session.userId());
        if (userIds.isEmpty()) return List.of();
        List<SubTask> tasks = subTaskRepository.findDepartmentSubTasks(userIds);
        List<Long> taskIds = tasks.stream().map(SubTask::getId).toList();
        Map<Long, List<PointLedger>> ledgersByTask = loadPointLedgers(taskIds);
        Map<Long, List<Map<String, Object>>> deliveriesByTask = Optional.ofNullable(
                        subTaskCommandService.getDeliveryVersionsByTaskIds(
                                tasks.stream().map(SubTask::getId).toList()))
                .orElse(Map.of());
        Rejections rejections = loadRejections(taskIds, taskProjectIds(tasks));
        return tasks.stream()
                .map(task -> {
                    Project project = task.getProject();
                    var history = rejections.forTask(task, deliveriesByTask.getOrDefault(task.getId(), List.of()));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", task.getId());
                    item.put("name", task.getName());
                    item.put("status", task.getStatus());
                    item.put(
                            "createdAt",
                            task.getCreatedAt() == null
                                    ? null
                                    : task.getCreatedAt().format(DTF));
                    item.put("plannedDate", task.getPlannedDate());
                    item.put("actualDate", task.getActualDate());
                    item.put(
                            "submittedForReviewAt",
                            Optional.ofNullable(task.getSubmittedForReviewAt())
                                    .map(time -> time.format(DTF))
                                    .orElse(null));
                    item.put("designerId", task.getDesignerId());
                    item.put("designerName", task.getDesignerName());
                    item.put("publisherId", task.getPublisherId());
                    item.put("publisherName", task.getPublisherName());
                    item.put("publisherRole", task.getPublisherRole());
                    item.put("assigneeRole", task.getAssigneeRole());
                    item.put("pointRuleCode", task.getPointRuleCode());
                    item.put("basePointSnapshot", task.getBasePointSnapshot());
                    item.put("supportsChangeTiers", task.isSupportsChangeTiers());
                    item.put("smallChangeNoteSnapshot", task.getSmallChangeNoteSnapshot());
                    item.put("largeChangeNoteSnapshot", task.getLargeChangeNoteSnapshot());
                    item.put("pendingChangeBonusRequestId", task.getPendingChangeBonusRequestId());
                    item.put("pendingChangeRuleCode", task.getPendingChangeRuleCode());
                    item.put("pendingChangeBonusPoints", task.getPendingChangeBonusPoints());
                    item.put("pendingChangeBonusReason", task.getPendingChangeBonusReason());
                    item.put("pendingChangeReferenceImagesJson", task.getPendingChangeReferenceImagesJson());
                    item.put("details", task.getDetails());
                    item.put("deliverables", task.getDeliverables());
                    item.put("referenceImagesJson", task.getReferenceImagesJson());
                    item.put("attachmentsJson", task.getAttachmentsJson());
                    item.put("reviewComments", task.getReviewComments());
                    item.put("projectId", project.getId());
                    item.put("projectType", project.getType());
                    item.put("projectName", projectDisplayName(project));
                    item.put("readOnly", true);
                    item.put("plannerId", project.getPlannerId());
                    item.put("plannerName", project.getPlannerName());
                    item.put("rejectionRecords", history.rejections());
                    item.put("deliveryVersions", history.deliveries());
                    item.put(
                            "pointSummary",
                            SubTaskPointView.summary(
                                    ledgersByTask.getOrDefault(task.getId(), List.of()), history.deliveries()));
                    String uid = session.userId();
                    item.put("relation", uid.equals(task.getPublisherId()) ? "publisher" : "department_member");
                    item.put("relationLabel", uid.equals(task.getPublisherId()) ? "我发布的任务" : "部门成员关联任务");
                    return item;
                })
                .toList();
    }

    public ProjectDetailDTO toDetail(Project p) {
        Project loaded =
                p.getId() == null ? p : projectService.getProjectById(p.getId()).orElse(p);
        List<ActivityLog> logs =
                loaded.getId() == null ? List.of() : activityLogRepository.findProjectHistory(loaded.getId());
        return toDetail(loaded, null, logs);
    }

    public ProjectDetailDTO toDetailWithLogs(Project p, List<ActivityLog> logs) {
        return toDetail(p, null, logs);
    }

    public ProjectDetailDTO toDetail(Project p, Map<Long, List<Map<String, Object>>> preloadedScoring) {
        return toDetail(p, preloadedScoring, true);
    }

    public ProjectDetailDTO toDetail(
            Project p, Map<Long, List<Map<String, Object>>> preloadedScoring, boolean includeLogs) {
        return toDetail(p, preloadedScoring, includeLogs, null);
    }

    private ProjectDetailDTO toDetail(
            Project p, Map<Long, List<Map<String, Object>>> preloadedScoring, List<ActivityLog> preloadedLogs) {
        return toDetail(p, preloadedScoring, true, preloadedLogs);
    }

    private ProjectDetailDTO toDetail(
            Project p,
            Map<Long, List<Map<String, Object>>> preloadedScoring,
            boolean includeLogs,
            List<ActivityLog> preloadedLogs) {
        ProjectDetailDTO dto = new ProjectDetailDTO();
        List<ActivityLog> effectiveLogs = preloadedLogs != null
                ? preloadedLogs
                : (p.getId() == null ? List.of() : activityLogRepository.findProjectHistory(p.getId()));
        dto.setId(p.getId());
        dto.setProjectCode(p.getProjectCode());
        dto.setType(p.getType());
        String computedStatus = projectService.computeProjectStatus(p);
        dto.setStatus(computedStatus);
        Map<String, String> statusInfo = ProjectService.getProjectStatusInfo(computedStatus);
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setSalesName(p.getSalesName());
        dto.setSalesId(p.getSalesId());
        dto.setPlannerName(p.getPlannerName());
        dto.setPlannerId(p.getPlannerId());
        dto.setProductName(p.getProductName());
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());
        dto.setDescription(p.getDescription());
        dto.setProductCategory(
                p.getProductCategory() != null ? p.getProductCategory().getName() : null);
        dto.setProductCategoryNote(p.getProductCategoryNote());
        dto.setTargetMarket(p.getTargetMarket());
        dto.setComplianceItems(p.getComplianceItems());
        dto.setPriceRange(p.getPriceRange());
        dto.setIpName(p.getIpName());
        dto.setIpSubOptions(p.getIpSubOptions());
        dto.setReferenceImagesJson(p.getReferenceImagesJson());
        dto.setAttachmentsJson(p.getAttachmentsJson());
        dto.setProductArchiveJson(p.getProductArchiveJson());

        if (includeLogs)
            dto.setLogs(effectiveLogs.stream()
                    .map(l -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("time", l.getTime().format(DTF));
                        m.put("action", l.getAction());
                        m.put("user", l.getUsername());
                        m.put("role", l.getRole());
                        m.put("entityType", l.getEntityType());
                        m.put("entityId", l.getEntityId());
                        m.put("beforeData", l.getBeforeData());
                        m.put("afterData", l.getAfterData());
                        m.put("changedFields", l.getChangedFields());
                        return m;
                    })
                    .collect(Collectors.toList()));

        List<SubTask> taskList = p.getTasks();
        List<Long> taskIds = taskList.stream().map(SubTask::getId).collect(Collectors.toList());
        Map<Long, List<Map<String, Object>>> deliveriesByTask = Optional.ofNullable(
                        subTaskCommandService.getDeliveryVersionsByTaskIds(taskIds))
                .orElse(Map.of());
        Map<Long, List<PointLedger>> ledgersByTask = loadPointLedgers(taskIds);
        Map<Long, List<Map<String, Object>>> scoringMap = preloadedScoring != null ? preloadedScoring : new HashMap<>();
        if (preloadedScoring == null && !taskIds.isEmpty()) {
            scoringRepository.findBySubTaskIds(taskIds).forEach(sr -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", sr.getId());
                m.put("role", sr.getRole());
                m.put("scoreType", sr.getScoreType());
                m.put("reviewStage", sr.getReviewStage());
                m.put("reviewStatus", sr.getReviewStatus());
                m.put("reviewerId", sr.getReviewerId());
                m.put("reviewerName", sr.getReviewerName());
                m.put(
                        "reviewedAt",
                        sr.getReviewedAt() != null ? sr.getReviewedAt().format(DTF) : null);
                m.put("score", sr.getScore());
                m.put("comment", sr.getComment());
                m.put("aesthetics", sr.getAesthetics());
                m.put("innovation", sr.getInnovation());
                m.put("weight", subTaskCommandService.currentScoringWeight(p.getType(), sr.getRole()));
                scoringMap
                        .computeIfAbsent(sr.getSubTask().getId(), k -> new ArrayList<>())
                        .add(m);
            });
        }
        Rejections rejections = loadRejections(taskIds, p.getId() == null ? List.of() : List.of(p.getId()));
        dto.setTasks(taskList.stream()
                .map(t -> {
                    TaskDetailDTO taskDto = toTaskDetail(t);
                    taskDto.setScoringRecords(scoringMap.getOrDefault(t.getId(), List.of()));
                    var history = rejections.forTask(t, deliveriesByTask.getOrDefault(t.getId(), List.of()));
                    taskDto.setRejectionRecords(history.rejections());
                    rejections.cycles().getOrDefault(t.getId(), List.of()).stream()
                            .filter(c -> "rejected".equals(t.getStatus()))
                            .filter(c -> "ACTIVE".equals(c.getStatus()))
                            .max(Comparator.comparing(SubTaskRejectionCycle::getSequenceNo))
                            .ifPresent(c -> {
                                taskDto.setActiveRejectionCycleId(c.getId());
                                taskDto.setActiveRejectionRole(c.getRejectionRole());
                            });
                    taskDto.setDeliveryVersions(history.deliveries());
                    taskDto.setPointSummary(SubTaskPointView.summary(
                            ledgersByTask.getOrDefault(t.getId(), List.of()), history.deliveries()));
                    return taskDto;
                })
                .collect(Collectors.toList()));
        dto.setSubTaskWorkflow(projectWorkflowService.build(p));

        int taskCount = p.getTasks().size();
        long doneCount = p.getTasks().stream()
                .filter(t -> List.of("delivered", "planner_approved", "sales_approved", "admin_approved", "completed")
                        .contains(t.getStatus()))
                .count();
        dto.setProgressPercent(taskCount > 0 ? (int) (doneCount * 100 / taskCount) : 0);

        dto.setCreatedAt(p.getCreatedAt().format(DTF));
        dto.setUpdatedAt(p.getUpdatedAt().format(DTF));
        dto.setFeishuChatId(p.getFeishuChatId());
        dto.setFeishuChatStatus(p.getFeishuChatStatus());
        dto.setFeishuChatError(p.getFeishuChatError());
        dto.setFeishuChatEnabled(p.isFeishuChatEnabled());
        return dto;
    }

    private List<Long> taskProjectIds(List<SubTask> tasks) {
        return tasks.stream().map(task -> task.getProject().getId()).distinct().toList();
    }

    private Rejections loadRejections(List<Long> taskIds, List<Long> projectIds) {
        if (taskIds.isEmpty()) return new Rejections(List.of(), Map.of());
        var logs = activityLogRepository.findSubTaskRejectionHistory(taskIds, projectIds);
        Map<Long, List<SubTaskRejectionCycle>> cycles = rejectionCycleRepository == null
                ? Map.of()
                : rejectionCycleRepository.findBySubTaskIdInOrderBySubTaskIdAscSequenceNoAsc(taskIds).stream()
                        .collect(Collectors.groupingBy(
                                cycle -> cycle.getSubTask().getId()));
        return new Rejections(logs, cycles);
    }

    private record Rejections(List<ActivityLog> logs, Map<Long, List<SubTaskRejectionCycle>> cycles) {
        SubTaskRejectionHistory.View forTask(SubTask task, List<Map<String, Object>> versions) {
            return SubTaskRejectionHistory.build(task, logs, cycles.getOrDefault(task.getId(), List.of()), versions);
        }
    }

    public Map<Long, List<Map<String, Object>>> loadScoringDetails(List<Long> taskIds) {
        Map<Long, List<Map<String, Object>>> scoringMap = new HashMap<>();
        if (taskIds.isEmpty()) return scoringMap;
        scoringRepository.findBySubTaskIds(taskIds).forEach(sr -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sr.getId());
            m.put("role", sr.getRole());
            m.put("scoreType", sr.getScoreType());
            m.put("reviewStage", sr.getReviewStage());
            m.put("reviewStatus", sr.getReviewStatus());
            m.put("reviewerId", sr.getReviewerId());
            m.put("reviewerName", sr.getReviewerName());
            m.put("reviewedAt", sr.getReviewedAt() != null ? sr.getReviewedAt().format(DTF) : null);
            m.put("score", sr.getScore());
            m.put("comment", sr.getComment());
            m.put("aesthetics", sr.getAesthetics());
            m.put("innovation", sr.getInnovation());
            m.put("weight", sr.getWeight());
            scoringMap
                    .computeIfAbsent(sr.getSubTask().getId(), ignored -> new ArrayList<>())
                    .add(m);
        });
        return scoringMap;
    }

    public String projectDisplayName(Project project) {
        if (project.getProductName() != null && !project.getProductName().isBlank()) {
            return project.getProductName().trim();
        }
        return project.getProductRequirements();
    }

    private TaskDetailDTO toTaskDetail(SubTask task) {
        TaskDetailDTO dto = new TaskDetailDTO();
        dto.setId(task.getId());
        dto.setName(task.getName());
        dto.setStatus(task.getStatus());
        Map<String, String> statusInfo = ProjectService.getTaskStatusInfo(task.getStatus());
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setStatusIcon(statusInfo.get("icon"));
        dto.setWorkflowStage(task.getWorkflowStage());
        dto.setPlannedDate(task.getPlannedDate());
        dto.setActualDate(task.getActualDate());
        dto.setDesignerId(task.getDesignerId());
        dto.setDesignerName(task.getDesignerName());
        dto.setPointRuleCode(task.getPointRuleCode());
        dto.setBasePointSnapshot(task.getBasePointSnapshot());
        dto.setSupportsChangeTiers(task.isSupportsChangeTiers());
        dto.setSmallChangeNoteSnapshot(task.getSmallChangeNoteSnapshot());
        dto.setLargeChangeNoteSnapshot(task.getLargeChangeNoteSnapshot());
        dto.setDifficultyMultiplierSnapshot(task.getDifficultyMultiplierSnapshot());
        dto.setConceptReserveExempt(task.isConceptReserveExempt());
        dto.setPendingChangeRuleCode(task.getPendingChangeRuleCode());
        dto.setPendingChangeBonusRequestId(task.getPendingChangeBonusRequestId());
        dto.setPendingChangeBonusPoints(task.getPendingChangeBonusPoints());
        dto.setPendingChangeBonusReason(task.getPendingChangeBonusReason());
        dto.setPendingChangeReferenceImagesJson(task.getPendingChangeReferenceImagesJson());
        dto.setQualityBonusThresholdSnapshot(task.getQualityBonusThresholdSnapshot());
        dto.setQualityBonusRatioSnapshot(task.getQualityBonusRatioSnapshot());
        dto.setQualityTopThresholdSnapshot(task.getQualityTopThresholdSnapshot());
        dto.setQualityTopRatioSnapshot(task.getQualityTopRatioSnapshot());
        dto.setMaxTotalMultiplierSnapshot(task.getMaxTotalMultiplierSnapshot());
        dto.setCollaboratorAllocationsJson(task.getCollaboratorAllocationsJson());
        dto.setMilestoneMonth(task.getMilestoneMonth());
        dto.setAssignmentReason(task.getAssignmentReason());
        dto.setCountInPerformanceSnapshot(task.getCountInPerformanceSnapshot());
        dto.setRequiredSkillTagsJson(task.getRequiredSkillTagsJson());
        dto.setAllocationStatus(task.getAllocationStatus());
        dto.setMarketPublishedAt(
                task.getMarketPublishedAt() == null
                        ? null
                        : task.getMarketPublishedAt().format(DTF));
        dto.setClaimedAt(
                task.getClaimedAt() == null ? null : task.getClaimedAt().format(DTF));
        dto.setAssigneeRole(task.getAssigneeRole());
        dto.setDetails(task.getDetails());
        dto.setDeliverables(task.getDeliverables());
        dto.setAttachmentsJson(task.getAttachmentsJson());
        dto.setReferenceImagesJson(task.getReferenceImagesJson());
        dto.setReviewComments(task.getReviewComments());
        dto.setSelfScore(task.getSelfScore());
        dto.setSelfAesthetics(task.getSelfAesthetics());
        dto.setSelfInnovation(task.getSelfInnovation());
        dto.setCreatedAt(task.getCreatedAt().format(DTF));
        return dto;
    }

    private Map<Long, List<PointLedger>> loadPointLedgers(List<Long> taskIds) {
        if (pointLedgerRepository == null || taskIds.isEmpty()) return Map.of();
        return pointLedgerRepository.findBySubTaskIdIn(taskIds).stream()
                .collect(Collectors.groupingBy(PointLedger::getSubTaskId));
    }
}
