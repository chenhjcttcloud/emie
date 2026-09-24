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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper JSON = new ObjectMapper();

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
        Map<Long, List<ActivityLog>> logsByProject = new HashMap<>();
        return tasks.stream()
                .map(task -> {
                    Project project = task.getProject();
                    List<ActivityLog> projectLogs = logsByProject.computeIfAbsent(
                            project.getId(), id -> activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(id));
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
                    item.put("scoringRecords", scoringByTask.getOrDefault(task.getId(), List.of()));
                    item.put("rejectionRecords", rejectionRecords(project, task, projectLogs));
                    item.put("deliveryVersions", deliveriesByTask.getOrDefault(task.getId(), List.of()));
                    item.put("relation", session.userId().equals(task.getDesignerId()) ? "assignee" : "publisher");
                    List<PointLedger> ledgers = ledgersByTask.getOrDefault(task.getId(), List.of());
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
        Map<Long, List<Map<String, Object>>> deliveriesByTask = Optional.ofNullable(
                        subTaskCommandService.getDeliveryVersionsByTaskIds(
                                tasks.stream().map(SubTask::getId).toList()))
                .orElse(Map.of());
        Map<Long, List<ActivityLog>> logsByProject = new HashMap<>();
        return tasks.stream()
                .map(task -> {
                    Project project = task.getProject();
                    List<ActivityLog> projectLogs = logsByProject.computeIfAbsent(
                            project.getId(), id -> activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(id));
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
                    item.put("designerId", task.getDesignerId());
                    item.put("designerName", task.getDesignerName());
                    item.put("publisherId", task.getPublisherId());
                    item.put("publisherName", task.getPublisherName());
                    item.put("publisherRole", task.getPublisherRole());
                    item.put("assigneeRole", task.getAssigneeRole());
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
                    item.put("rejectionRecords", rejectionRecords(project, task, projectLogs));
                    item.put("deliveryVersions", deliveriesByTask.getOrDefault(task.getId(), List.of()));
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
        List<ActivityLog> logs = loaded.getId() == null
                ? List.of()
                : activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(loaded.getId());
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
                : (p.getId() == null
                        ? List.of()
                        : activityLogRepository.findTop200ByProjectIdOrderByTimeDesc(p.getId()));
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
        Map<Long, List<SubTaskRejectionCycle>> rejectionCycles = rejectionCycleRepository == null
                ? Map.of()
                : rejectionCycleRepository.findBySubTaskIdInOrderBySubTaskIdAscSequenceNoAsc(taskIds).stream()
                        .collect(Collectors.groupingBy(
                                c -> c.getSubTask().getId(), LinkedHashMap::new, Collectors.toList()));
        dto.setTasks(taskList.stream()
                .map(t -> {
                    TaskDetailDTO taskDto = toTaskDetail(t);
                    taskDto.setScoringRecords(scoringMap.getOrDefault(t.getId(), List.of()));
                    List<SubTaskRejectionCycle> cycles = rejectionCycles.getOrDefault(t.getId(), List.of());
                    taskDto.setRejectionRecords(rejectionRecords(p, t, effectiveLogs, cycles));
                    cycles.stream()
                            .filter(c -> "ACTIVE".equals(c.getStatus()))
                            .findFirst()
                            .ifPresent(c -> {
                                taskDto.setActiveRejectionCycleId(c.getId());
                                taskDto.setActiveRejectionRole(c.getRejectionRole());
                            });
                    taskDto.setDeliveryVersions(subTaskCommandService.getDeliveryVersions(t.getId()));
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

    private List<Map<String, Object>> rejectionRecords(
            Project project, SubTask task, List<ActivityLog> preloadedLogs, List<SubTaskRejectionCycle> cycles) {
        String legacyPrefix = "子任务驳回：" + task.getName() + "（意见：";
        List<ActivityLog> logs = (preloadedLogs != null ? preloadedLogs : project.getLogs())
                .stream()
                        .filter(log ->
                                log.getAction() != null && log.getAction().startsWith("子任务驳回："))
                        .filter(log -> ("sub_task".equals(log.getEntityType())
                                        && Objects.equals(log.getEntityId(), task.getId()))
                                || (!"sub_task".equals(log.getEntityType())
                                        && log.getAction().startsWith(legacyPrefix)))
                        .sorted(Comparator.comparing(ActivityLog::getTime))
                        .toList();
        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < logs.size(); index++) {
            ActivityLog log = logs.get(index);
            Map<String, Object> snapshot = parseJsonMap(log.getBeforeData());
            Map<String, Object> rejection = parseJsonMap(log.getAfterData());
            String action = log.getAction();
            int reasonStart = action.indexOf("（意见：");
            String reason = reasonStart >= 0
                    ? action.substring(reasonStart + 4, action.endsWith("）") ? action.length() - 1 : action.length())
                    : "";
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", log.getId());
            record.put("attemptNo", index + 1);
            record.put("reviewerName", log.getUsername());
            record.put("reviewerRole", log.getRole());
            record.put("reviewedAt", log.getTime().format(DTF));
            record.put("reason", reason);
            record.put(
                    "requiredCompletionDate",
                    rejection.getOrDefault("requiredCompletionDate", snapshot.getOrDefault("plannedDate", "")));
            record.put("rejectionReferenceImagesJson", rejection.getOrDefault("rejectionReferenceImagesJson", "[]"));
            record.put("rejectionAttachmentsJson", rejection.getOrDefault("rejectionAttachmentsJson", "[]"));
            record.put("deliverables", snapshot.getOrDefault("deliverables", task.getDeliverables()));
            record.put(
                    "referenceImagesJson", snapshot.getOrDefault("referenceImagesJson", task.getReferenceImagesJson()));
            record.put("attachmentsJson", snapshot.getOrDefault("attachmentsJson", task.getAttachmentsJson()));
            record.put("actualDate", snapshot.getOrDefault("actualDate", task.getActualDate()));
            record.put("submittedByName", snapshot.getOrDefault("submittedByName", task.getDesignerName()));
            record.put("legacy", snapshot.isEmpty());
            Long rejectionCycleId = rejection.get("rejectionCycleId") instanceof Number n ? n.longValue() : null;
            cycles.stream()
                    .filter(c -> Objects.equals(c.getId(), rejectionCycleId))
                    .findFirst()
                    .ifPresent(c -> {
                        record.put("cycleId", c.getId());
                        record.put("cancelled", "CANCELLED".equals(c.getStatus()));
                        record.put(
                                "cancelledAt",
                                c.getCancelledAt() == null
                                        ? null
                                        : c.getCancelledAt().format(DTF));
                        record.put("cancelledByName", c.getCancelledByName());
                    });
            records.add(record);
        }
        return records;
    }

    private List<Map<String, Object>> rejectionRecords(Project project, SubTask task, List<ActivityLog> preloadedLogs) {
        List<SubTaskRejectionCycle> cycles = rejectionCycleRepository == null
                ? List.of()
                : rejectionCycleRepository.findBySubTaskIdInOrderBySubTaskIdAscSequenceNoAsc(List.of(task.getId()));
        return rejectionRecords(project, task, preloadedLogs, cycles);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
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
}
