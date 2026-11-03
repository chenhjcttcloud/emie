package com.emie.designpm.sync.service;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.feishu.service.FeishuBaseService;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskDeliveryVersionRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.util.*;

/** 在短事务内构造纯数据快照；返回的动作只持有 DTO/标量，在事务结束后执行外部请求。 */
final class SyncPayloadFactory {
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;
    private final FeishuBaseService feishuBaseService;
    private final SubTaskDeliveryVersionRepository deliveryVersionRepository;

    SyncPayloadFactory(
            ProjectRepository projects,
            SubTaskRepository tasks,
            ScoringRepository scores,
            ActivityLogRepository logs,
            FeishuBaseService feishu,
            SubTaskDeliveryVersionRepository versions) {
        projectRepository = projects;
        subTaskRepository = tasks;
        scoringRepository = scores;
        activityLogRepository = logs;
        feishuBaseService = feishu;
        deliveryVersionRepository = versions;
    }

    Action prepare(SyncQueue item) {
        Long id = item.getEntityId();
        boolean includeBackup = !"reconcile".equals(item.getAction());
        boolean delete = "delete".equals(item.getAction());
        return switch (item.getEntityType()) {
            case "project" -> delete ? deleteProject(id) : syncProject(id, includeBackup);
            case "sub_task" -> delete
                    ? () -> feishuBaseService.deleteSubTaskRecord(id)
                    : syncSubTask(id, includeBackup);
            case "scoring_record" -> delete
                    ? () -> feishuBaseService.deleteScoringRecord(id)
                    : syncScoring(id, includeBackup);
            case "activity_log" -> syncActivityLog(id, includeBackup);
            default -> throw new IllegalArgumentException("未知同步类型: " + item.getEntityType());
        };
    }

    private Action syncProject(Long projectId, boolean includeBackup) {
        Project p = projectRepository
                .findByIdWithTasks(projectId)
                .or(() -> projectRepository.findById(projectId))
                .orElseThrow(() -> new IllegalStateException("项目不存在: " + projectId));

        int taskCount = p.getTasks() != null ? p.getTasks().size() : 0;
        int doneCount = p.getTasks() != null
                ? (int) p.getTasks().stream()
                        .filter(t -> List.of("approved", "completed", "sales_approved", "admin_approved")
                                .contains(t.getStatus()))
                        .count()
                : 0;
        int progress = taskCount > 0 ? (doneCount * 100 / taskCount) : 0;

        List<ScoringRecord> reviewRecords = scoringRepository.findByProjectIds(List.of(projectId));
        int expectedReviewCount = taskCount * 2;
        int approvedReviewCount =
                (int) reviewRecords.stream().filter(this::isApprovedReview).count();
        int reviewProgress = expectedReviewCount > 0 ? approvedReviewCount * 100 / expectedReviewCount : 0;
        String projectFlow = projectFlowLabel(p.getStatus(), p.getWorkflowStage(), p.getWorkflowStatus());
        String currentReviewStage = currentReviewStage(p, reviewRecords);

        String categoryName =
                p.getProductCategory() != null ? p.getProductCategory().getName() : null;

        List<Long> taskIds = p.getTasks() == null
                ? List.of()
                : p.getTasks().stream().map(SubTask::getId).toList();
        FeishuBaseService.V2ProjectData data = new FeishuBaseService.V2ProjectData(
                p.getId(),
                p.getProjectCode(),
                p.getProductName(),
                p.getType(),
                p.getStatus(),
                p.getSalesName(),
                p.getPlannerName(),
                categoryName,
                p.getPriceRange(),
                p.getReferenceImagesJson(),
                p.getAttachmentsJson(),
                taskCount,
                p.getCreatedAt(),
                p.getDeadline(),
                p.getUpdatedAt(),
                taskIds,
                taskProgressSummary(p.getTasks()),
                progress,
                projectFlowLabel(p.getStatus(), p.getWorkflowStage(), p.getWorkflowStatus()),
                progress,
                p.getDescription());
        if (feishuBaseService.isV2Active()) {
            return () -> feishuBaseService.syncV2Project(data, includeBackup);
        }
        Map<String, String> extraFields = projectExtraFields(p, data.taskStage());
        return () -> feishuBaseService.syncProject(
                data.id(),
                data.type(),
                data.status(),
                data.sales(),
                data.planner(),
                data.deadline(),
                data.category(),
                data.price(),
                taskCount,
                progress,
                projectFlow,
                currentReviewStage,
                reviewProgress,
                extraFields,
                data.createdAt());
    }

    private Action syncSubTask(Long taskId, boolean includeBackup) {
        SubTask t = subTaskRepository
                .findByIdWithProject(taskId)
                .or(() -> subTaskRepository.findById(taskId))
                .orElseThrow(() -> new IllegalStateException("子任务不存在: " + taskId));

        List<ScoringRecord> reviews = scoringRepository.findBySubTaskId(taskId);
        ScoringRecord firstReview = findReview(reviews, "first");
        ScoringRecord secondReview = findReview(reviews, "second");
        String projectType = t.getProject() != null ? t.getProject().getType() : "regular";

        if (feishuBaseService.isV2Active()) {
            Map<String, Integer> roleScores = new HashMap<>();
            double weighted = 0;
            double weights = 0;
            for (ScoringRecord review : reviews) {
                Integer score = reviewScore(review);
                if (score != null) roleScores.put(review.getRole(), score);
                if (score != null && review.getWeight() != null && review.getWeight() > 0) {
                    weighted += score * review.getWeight();
                    weights += review.getWeight();
                }
            }
            List<SubTaskDeliveryVersion> versions = deliveryVersionRepository == null
                    ? List.of()
                    : deliveryVersionRepository.findBySubTaskIdOrderByVersionNoDesc(taskId);
            SubTaskDeliveryVersion latest = versions.isEmpty() ? null : versions.get(0);
            String deliveryImages = latest != null ? latest.getReferenceImagesJson() : t.getReferenceImagesJson();
            String deliveryFiles = latest != null ? latest.getAttachmentsJson() : t.getAttachmentsJson();
            double aggregate = weights > 0 ? Math.round(weighted / weights * 10d) / 10d : 0;
            FeishuBaseService.V2TaskData data = new FeishuBaseService.V2TaskData(
                    t.getId(),
                    t.getProject() != null ? t.getProject().getId() : null,
                    t.getName(),
                    t.getStatus(),
                    latest == null ? t.getReferenceImagesJson() : null,
                    latest == null ? t.getAttachmentsJson() : null,
                    t.getDesignerName(),
                    t.getDetails(),
                    t.getCreatedAt(),
                    t.getPlannedDate(),
                    t.getUpdatedAt(),
                    deliveryImages,
                    deliveryFiles,
                    t.getSelfScore(),
                    roleScores.get("sales"),
                    roleScores.get("planner"),
                    roleScores.get("admin"),
                    weights > 0 ? aggregate : null,
                    reviews.stream().map(ScoringRecord::getId).toList(),
                    workflowStageLabel(t.getWorkflowStage()),
                    taskProgress(t.getStatus()),
                    t.getReviewComments());
            return () -> feishuBaseService.syncV2Task(data, includeBackup);
        }

        String actualDate = t.getActualDate();
        if ((actualDate == null || actualDate.isBlank()) && deliveryVersionRepository != null) {
            actualDate = deliveryVersionRepository
                    .findFirstBySubTaskIdAndActualDateIsNotNullOrderByVersionNoDesc(taskId)
                    .map(SubTaskDeliveryVersion::getActualDate)
                    .filter(value -> !value.isBlank())
                    .orElse(null);
        }

        FeishuBaseService.SubTaskSyncData data = new FeishuBaseService.SubTaskSyncData(
                t.getId(),
                t.getName(),
                t.getStatus(),
                t.getDesignerName(),
                t.getPlannedDate(),
                actualDate,
                t.getSelfScore(),
                t.getProject() != null ? t.getProject().getId() : null,
                reviewRole(firstReview, "planner"),
                reviewStatus(firstReview),
                reviewScore(firstReview),
                reviewName(firstReview),
                reviewRole(secondReview, "channel_custom".equals(projectType) ? "sales" : "admin"),
                reviewStatus(secondReview),
                reviewScore(secondReview),
                reviewName(secondReview),
                finalReviewScore(firstReview, secondReview),
                t.getCreatedAt(),
                subTaskExtraFields(t));
        return () -> feishuBaseService.syncSubTask(data);
    }

    private Map<String, String> projectExtraFields(Project p, String taskProgress) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("项目编号", p.getProjectCode());
        values.put("产品名称", p.getProductName());
        values.put("项目名称", p.getProductName());
        values.put("需求说明", p.getProductRequirements());
        values.put("项目描述", p.getDescription());
        values.put("目标市场", p.getTargetMarket());
        values.put("合规项", p.getComplianceItems());
        values.put("IP名称", p.getIpName());
        values.put("创意作者", p.getCreativeAuthorName());
        values.put("来源", p.getSource());
        values.put("产品类目备注", p.getProductCategoryNote());
        values.put("子任务流程", taskProgress);
        return values;
    }

    static String projectFlowLabel(String projectStatus, String workflowStage, String workflowStatus) {
        String terminal =
                switch (projectStatus == null ? "" : projectStatus) {
                    case "draft" -> "草稿";
                    case "pending_planner" -> "待企划接单";
                    case "paused" -> "已暂停";
                    case "pending_terminate" -> "终止确认中";
                    case "terminated" -> "已终止";
                    case "completed" -> "已完成";
                    default -> null;
                };
        if (terminal != null) return terminal;
        String stage = workflowStageLabel(workflowStage);
        if (stage == null) stage = "未开始";
        return switch (workflowStatus == null ? "" : workflowStatus) {
            case "under_review" -> stage + "（审核中）";
            case "rejected" -> stage + "（已驳回）";
            case "completed" -> stage + "（已完成）";
            default -> stage;
        };
    }

    static String taskProgressSummary(List<SubTask> tasks) {
        List<SubTask> safeTasks = tasks == null ? List.of() : tasks;
        int total = safeTasks.size();
        int completed = 0;
        int reviewing = 0;
        int active = 0;
        int pending = 0;
        int rejected = 0;
        for (SubTask task : safeTasks) {
            String status = task.getStatus();
            if (List.of("approved", "completed", "sales_approved", "admin_approved")
                    .contains(status)) completed++;
            else if (List.of("delivered", "submitted_for_review", "planner_approved", "scoring_planner")
                    .contains(status)) reviewing++;
            else if ("pending".equals(status)) pending++;
            else if ("rejected".equals(status)) rejected++;
            else active++;
        }
        List<String> parts = new ArrayList<>();
        parts.add("已完成 " + completed + "/" + total);
        if (reviewing > 0) parts.add("送审中 " + reviewing);
        if (active > 0) parts.add("进行中 " + active);
        if (pending > 0) parts.add("待认领 " + pending);
        if (rejected > 0) parts.add("已驳回 " + rejected);
        return String.join("｜", parts);
    }

    private Map<String, String> subTaskExtraFields(SubTask t) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("所属阶段", workflowStageLabel(t.getWorkflowStage()));
        values.put("负责人类型", assigneeRoleLabel(t.getAssigneeRole()));
        values.put("发布人", t.getPublisherName());
        values.put("细节要求说明", t.getDetails());
        values.put("交付成果", t.getDeliverables());
        values.put("交付成果/完成说明", t.getDeliverables());
        values.put("审核意见", t.getReviewComments());
        return values;
    }

    static String workflowStageLabel(String stage) {
        if (stage == null) return null;
        return switch (stage) {
            case "design" -> "设计";
            case "design_review" -> "设计送审";
            case "three_d_review" -> "3D送审";
            case "sample_review" -> "打样送审";
            case "promotion" -> "产品宣发";
            case "bulk" -> "大货";
            default -> stage;
        };
    }

    private String assigneeRoleLabel(String role) {
        if (role == null) return null;
        return switch (role) {
            case "designer" -> "设计师";
            case "supplychain" -> "供应链";
            case "planner" -> "企划";
            case "sales" -> "销售";
            case "promotion" -> "产品推广";
            default -> role;
        };
    }

    static String latestDeliveryActualDate(List<SubTaskDeliveryVersion> versions) {
        if (versions == null) return null;
        return versions.stream()
                .map(SubTaskDeliveryVersion::getActualDate)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private Action syncScoring(Long recordId, boolean includeBackup) {
        ScoringRecord r = scoringRepository
                .findByIdWithTaskAndProject(recordId)
                .or(() -> scoringRepository.findById(recordId))
                .orElseThrow(() -> new IllegalStateException("评分记录不存在: " + recordId));

        SubTask task = r.getSubTask();
        Project project = task != null ? task.getProject() : null;
        if (feishuBaseService.isV2Active()) {
            Integer score = reviewScore(r);
            Double weighted = score == null ? null : score * (r.getWeight() == null ? 1d : r.getWeight());
            FeishuBaseService.V2ScoringData data = new FeishuBaseService.V2ScoringData(
                    r.getId(),
                    r.getRole(),
                    r.getReviewerName(),
                    task != null ? task.getId() : null,
                    r.getReviewedAt(),
                    score == null ? null : score.doubleValue(),
                    r.getWeight(),
                    weighted,
                    r.getComment());
            return () -> feishuBaseService.syncV2Scoring(data, includeBackup);
        }
        FeishuBaseService.ScoringSyncData data = new FeishuBaseService.ScoringSyncData(
                r.getId(),
                r.getRole(),
                r.getScore(),
                r.getWeight(),
                task != null ? task.getId() : null,
                project != null ? project.getId() : null,
                project != null ? project.getType() : null,
                normalizedReviewStage(r),
                normalizedReviewStatus(r),
                r.getReviewerName(),
                r.getComment(),
                r.getReviewedAt());
        return () -> feishuBaseService.syncScoring(data);
    }

    private String currentReviewStage(Project project, List<ScoringRecord> reviews) {
        if (project.getTasks() == null || project.getTasks().isEmpty()) return "未进入审核";
        if (project.getTasks().stream().allMatch(t -> "completed".equals(t.getStatus()))) return "审核完成";
        if (reviews.stream()
                .anyMatch(
                        r -> "second".equals(normalizedReviewStage(r)) && "rejected".equals(normalizedReviewStatus(r))))
            return "二审已驳回";
        if (reviews.stream()
                .anyMatch(
                        r -> "first".equals(normalizedReviewStage(r)) && "rejected".equals(normalizedReviewStatus(r))))
            return "一审已驳回";
        if (project.getTasks().stream().anyMatch(t -> "planner_approved".equals(t.getStatus()))) return "二审中";
        if (project.getTasks().stream().anyMatch(t -> "delivered".equals(t.getStatus()))) return "一审中";
        return "未进入审核";
    }

    private ScoringRecord findReview(List<ScoringRecord> reviews, String stage) {
        return reviews.stream()
                .filter(r -> stage.equals(r.getReviewStage()))
                .findFirst()
                .orElseGet(() -> reviews.stream()
                        .filter(r -> "first".equals(stage) == "planner".equals(r.getRole()))
                        .findFirst()
                        .orElse(null));
    }

    private boolean isApprovedReview(ScoringRecord record) {
        return "approved".equals(normalizedReviewStatus(record));
    }

    private String normalizedReviewStatus(ScoringRecord record) {
        if (record == null) return "pending";
        if (record.getReviewStatus() != null && !record.getReviewStatus().isBlank()) {
            return record.getReviewStatus();
        }
        return record.getScore() != null || (record.getAesthetics() != null && record.getInnovation() != null)
                ? "approved"
                : "pending";
    }

    private String normalizedReviewStage(ScoringRecord record) {
        if (record != null
                && record.getReviewStage() != null
                && !record.getReviewStage().isBlank()) {
            return record.getReviewStage();
        }
        return record != null && "planner".equals(record.getRole()) ? "first" : "second";
    }

    private String reviewRole(ScoringRecord review, String defaultRole) {
        return review != null ? review.getRole() : defaultRole;
    }

    private String reviewStatus(ScoringRecord review) {
        return normalizedReviewStatus(review);
    }

    private Integer reviewScore(ScoringRecord review) {
        if (review == null) return null;
        if (review.getScore() != null) return review.getScore();
        if (review.getAesthetics() != null && review.getInnovation() != null) {
            return (int) Math.round((review.getAesthetics() + review.getInnovation()) * 5.0);
        }
        return null;
    }

    private String reviewName(ScoringRecord review) {
        return review != null ? review.getReviewerName() : null;
    }

    private Double finalReviewScore(ScoringRecord firstReview, ScoringRecord secondReview) {
        Integer firstScore = reviewScore(firstReview);
        Integer secondScore = reviewScore(secondReview);
        if (!isApprovedReview(firstReview)
                || !isApprovedReview(secondReview)
                || firstScore == null
                || secondScore == null) {
            return null;
        }
        double firstWeight = firstReview.getWeight() != null ? firstReview.getWeight() : 1.0;
        double secondWeight = secondReview.getWeight() != null ? secondReview.getWeight() : 1.0;
        double totalWeight = firstWeight + secondWeight;
        if (totalWeight <= 0) return null;
        return Math.round(((firstScore * firstWeight + secondScore * secondWeight) / totalWeight) * 10.0) / 10.0;
    }

    private Action syncActivityLog(Long logId, boolean includeBackup) {
        ActivityLog log =
                activityLogRepository.findById(logId).orElseThrow(() -> new IllegalStateException("操作日志不存在: " + logId));
        if (feishuBaseService.isV2Active()) {
            FeishuBaseService.V2LogData data = new FeishuBaseService.V2LogData(
                    log.getId(),
                    log.getTime(),
                    log.getRole(),
                    log.getUsername(),
                    log.getAction(),
                    log.getChangedFields());
            return () -> feishuBaseService.syncV2Log(data, includeBackup);
        }
        Long projectId = log.getProjectRefId();
        FeishuBaseService.V2LogData data = new FeishuBaseService.V2LogData(
                log.getId(), log.getTime(), log.getRole(), log.getUsername(), log.getAction(), log.getChangedFields());
        return () -> feishuBaseService.syncActivityLog(
                data.id(), data.action(), data.user(), data.role(), projectId, data.time());
    }

    private double taskProgress(String status) {
        if (status == null) return 0;
        return switch (status) {
            case "completed", "approved", "sales_approved", "admin_approved" -> 100;
            case "delivered", "submitted_for_review", "planner_approved", "scoring_planner" -> 80;
            case "accepted", "in_progress", "rejected" -> 40;
            default -> 0;
        };
    }

    private Action deleteProject(Long projectId) {
        List<Action> deletes = new ArrayList<>();
        for (SubTask task : subTaskRepository.findByProjectIdOrderByCreatedAtAsc(projectId)) {
            for (ScoringRecord score : scoringRepository.findBySubTaskId(task.getId())) {
                Long scoreId = score.getId();
                deletes.add(() -> feishuBaseService.deleteScoringRecord(scoreId));
            }
            Long taskId = task.getId();
            deletes.add(() -> feishuBaseService.deleteSubTaskRecord(taskId));
        }
        return () -> {
            for (Action action : deletes) action.run();
            feishuBaseService.deleteProjectRecord(projectId);
        };
    }
}
