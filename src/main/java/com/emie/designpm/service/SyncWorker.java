package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 飞书同步工作线程
 * 定时消费 sync_queue 表，将数据写入飞书多维表格
 */
@Component
@ConditionalOnProperty(name = "app.feishu.sync-worker-enabled", havingValue = "true", matchIfMissing = true)
public class SyncWorker {
    private static final LocalDateTime SAFE_SYNC_CURSOR_FALLBACK = LocalDateTime.of(2000, 1, 1, 0, 0);

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);
    private static final int RECONCILE_ID_BATCH_SIZE = 500;
    private final ReentrantLock syncLock = new ReentrantLock();

    private final SyncQueueOperations syncQueueRepository;
    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;
    private final FeishuBaseService feishuBaseService;
    private final SyncQueueService syncQueueService;
    private final SystemConfigRepository systemConfigRepository;
    private final SubTaskDeliveryVersionRepository deliveryVersionRepository;
    /** 每条队列任务独立的主库短事务；队列状态更新走后台连接池，独立提交。 */
    private final TransactionTemplate itemTransaction;

    @Autowired
    public SyncWorker(@Qualifier("backgroundSyncQueueRepository") SyncQueueOperations syncQueueRepository,
                      ProjectRepository projectRepository,
                      SubTaskRepository subTaskRepository,
                      ScoringRepository scoringRepository,
                      ActivityLogRepository activityLogRepository,
                      FeishuBaseService feishuBaseService,
                      SyncQueueService syncQueueService,
                      SystemConfigRepository systemConfigRepository,
                      SubTaskDeliveryVersionRepository deliveryVersionRepository,
                      PlatformTransactionManager transactionManager) {
        this.syncQueueRepository = syncQueueRepository;
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
        this.feishuBaseService = feishuBaseService;
        this.syncQueueService = syncQueueService;
        this.systemConfigRepository = systemConfigRepository;
        this.deliveryVersionRepository = deliveryVersionRepository;
        this.itemTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /** 保留单元测试和旧调用方的构造签名；生产由 Spring 注入配置仓库。 */
    public SyncWorker(SyncQueueOperations syncQueueRepository,
                      ProjectRepository projectRepository,
                      SubTaskRepository subTaskRepository,
                      ScoringRepository scoringRepository,
                      ActivityLogRepository activityLogRepository,
                      FeishuBaseService feishuBaseService,
                      SyncQueueService syncQueueService) {
        this(syncQueueRepository, projectRepository, subTaskRepository, scoringRepository,
                activityLogRepository, feishuBaseService, syncQueueService, null, null, null);
    }

    SyncWorker(SyncQueueOperations syncQueueRepository,
               ProjectRepository projectRepository,
               SubTaskRepository subTaskRepository,
               ScoringRepository scoringRepository,
               ActivityLogRepository activityLogRepository,
               FeishuBaseService feishuBaseService,
               SyncQueueService syncQueueService,
               SystemConfigRepository systemConfigRepository,
               PlatformTransactionManager transactionManager) {
        this(syncQueueRepository, projectRepository, subTaskRepository, scoringRepository,
                activityLogRepository, feishuBaseService, syncQueueService, systemConfigRepository, null,
                transactionManager);
    }

    /** 每 30 秒消费队列 */
    @Scheduled(fixedDelay = 30_000)
    public void processQueue() {
        if (!syncLock.tryLock()) {
            log.debug("飞书同步队列跳过：已有同步轮次正在执行");
            return;
        }
        try {
            processQueueLocked();
        } finally {
            syncLock.unlock();
        }
    }

    private void processQueueLocked() {
        recoverStuckItems();
        List<SyncQueue> items = syncQueueRepository
                .findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        "pending", "pending", LocalDateTime.now());
        if (items.isEmpty()) return;

        log.info("飞书同步队列: {} 条待处理", items.size());

        for (SyncQueue item : items) {
            processItemInTransaction(item);
        }
    }

    /**
     * 每条任务在独立主库短事务中执行「读业务数据 + 处理 + 保存队列状态」，
     * 避免整轮循环（20 条 × 飞书 HTTP 调用）横跨单个长事务持有主库连接数分钟
     * 触发 prod 连接池 leak-detection 误报。队列状态更新经后台连接池独立提交，
     * 崩溃后可被 recoverStuckItems 恢复，调度与重试语义保持不变。
     */
    private void processItemInTransaction(SyncQueue item) {
        if (itemTransaction != null) {
            itemTransaction.executeWithoutResult(status -> processSingleItem(item));
        } else {
            processSingleItem(item);
        }
    }

    private void processSingleItem(SyncQueue item) {
        try {
            item.setStatus("processing");
            syncQueueRepository.saveQueue(item);

            switch (item.getEntityType()) {
                case "project" -> {
                    if ("delete".equals(item.getAction())) {
                        deleteProject(item.getEntityId());
                    } else {
                        syncProject(item.getEntityId(), !"reconcile".equals(item.getAction()));
                    }
                }
                case "sub_task" -> {
                    if ("delete".equals(item.getAction())) {
                        deleteSubTask(item.getEntityId());
                    } else {
                        syncSubTask(item.getEntityId(), !"reconcile".equals(item.getAction()));
                    }
                }
                case "scoring_record" -> {
                    if ("delete".equals(item.getAction())) {
                        deleteScoring(item.getEntityId());
                    } else {
                        syncScoring(item.getEntityId(), !"reconcile".equals(item.getAction()));
                    }
                }
                case "activity_log" -> syncActivityLog(item.getEntityId(), !"reconcile".equals(item.getAction()));
                default -> log.warn("未知同步类型: {}", item.getEntityType());
            }

            item.setStatus("done");
            item.setErrorMsg(null);
            item.setNextRetryAt(null);
            log.debug("同步成功: {} {}", item.getEntityType(), item.getEntityId());

        } catch (Exception e) {
            item.setRetryCount(item.getRetryCount() + 1);
            item.setErrorMsg(e.getMessage() != null ? e.getMessage().substring(0, Math.min(500, e.getMessage().length())) : "未知错误");
            if (item.getRetryCount() >= 3) {
                item.setStatus("fail");
                log.warn("同步失败(已重试3次): {} {} - {}", item.getEntityType(), item.getEntityId(), e.getMessage());
            } else {
                item.setStatus("pending");
                item.setNextRetryAt(LocalDateTime.now().plusSeconds(Math.min(900, 30L << Math.min(item.getRetryCount(), 4))));
                log.warn("同步失败(将重试 {}/3): {} {} - {}", item.getRetryCount(), item.getEntityType(), item.getEntityId(), e.getMessage());
            }
        } finally {
            syncQueueRepository.saveQueue(item);
        }
    }

    private void recoverStuckItems() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        List<SyncQueue> stuck = syncQueueRepository.findByStatusAndUpdatedAtBefore("processing", cutoff);
        for (SyncQueue item : stuck) {
            item.setStatus("pending");
            item.setErrorMsg("同步进程中断，已自动恢复");
            syncQueueRepository.saveQueue(item);
        }
        if (!stuck.isEmpty()) {
            log.warn("飞书同步队列恢复 {} 条卡住任务", stuck.size());
        }
    }

    /**
     * 周期性全量对账，用数据库当前记录补回飞书端被直接删除的数据。
     * 与 30 秒队列消费解耦，并仅在队列空闲时执行，避免持续全量写入和新任务饥饿。
     */
    @Scheduled(
            fixedDelayString = "${app.feishu.reconcile-delay-ms:3600000}",
            initialDelayString = "${app.feishu.reconcile-initial-delay-ms:300000}"
    )
    public void reconcileCurrentData() {
        if (!syncLock.tryLock()) {
            log.debug("飞书全量对账跳过：已有同步轮次正在执行");
            return;
        }
        try {
            reconcileCurrentDataLocked();
        } finally {
            syncLock.unlock();
        }
    }

    private void reconcileCurrentDataLocked() {
        if (syncQueueRepository.countByStatus("pending") > 0
                || syncQueueRepository.countByStatus("processing") > 0) {
            log.debug("飞书全量对账跳过：同步队列仍有待处理任务");
            return;
        }
        List<Long> projectIds = readIdsInBatches(projectRepository::findIdsAfter);
        List<Long> taskIds = readIdsInBatches(subTaskRepository::findIdsAfter);
        List<Long> scoringIds = readIdsInBatches(scoringRepository::findIdsAfter);
        List<Long> logIds = readIdsInBatches(activityLogRepository::findIdsAfter);

        try {
            Map<String, FeishuBaseService.MirrorReconcileResult> mirrorResult = feishuBaseService.reconcileMirrors(
                    new HashSet<>(projectIds), new HashSet<>(taskIds),
                    new HashSet<>(scoringIds), new HashSet<>(logIds));
            int skipped = mirrorResult.values().stream()
                    .mapToInt(FeishuBaseService.MirrorReconcileResult::skippedWithoutBackup).sum();
            if (skipped > 0) {
                log.warn("飞书主表镜像对账跳过 {} 条缺少系统备份的孤儿记录", skipped);
            }
        } catch (Exception e) {
            log.error("飞书主表镜像删除对账失败，本轮不执行全量重刷: {}", e.getMessage());
            return;
        }

        LocalDateTime until = LocalDateTime.now();
        // 主表按要求做全量对账；action=reconcile 保证这一轮不覆盖增量备份表。
        syncQueueService.enqueueAllForReconciliation("project", projectIds);
        syncQueueService.enqueueAllForReconciliation("sub_task", taskIds);
        syncQueueService.enqueueAllForReconciliation("scoring_record", scoringIds);
        syncQueueService.enqueueAllForReconciliation("activity_log", logIds);
        if (systemConfigRepository != null) {
            SystemConfig cursor = systemConfigRepository.findByConfigKey("feishu.sync.cursor")
                    .orElseGet(() -> SystemConfig.builder().configKey("feishu.sync.cursor").configGroup("system").valueType("text").description("飞书增量同步游标").build());
            cursor.setConfigValue(until.toString());
            systemConfigRepository.save(cursor);
        }
    }

    @FunctionalInterface
    private interface IdBatchReader {
        List<Long> read(Long afterId, Pageable pageRequest);
    }

    private List<Long> readIdsInBatches(IdBatchReader reader) {
        List<Long> ids = new ArrayList<>();
        long afterId = 0L;
        while (true) {
            List<Long> batch = reader.read(afterId, PageRequest.of(0, RECONCILE_ID_BATCH_SIZE));
            if (batch.isEmpty()) break;
            ids.addAll(batch);
            afterId = batch.get(batch.size() - 1);
            if (batch.size() < RECONCILE_ID_BATCH_SIZE) break;
        }
        return ids;
    }

    @FunctionalInterface
    private interface UpdatedIdBatchReader {
        List<Long> read(LocalDateTime after, LocalDateTime until, Pageable pageRequest);
    }

    private void enqueueUpdated(String type, UpdatedIdBatchReader reader, LocalDateTime after, LocalDateTime until) {
        List<Long> ids = new ArrayList<>();
        int page = 0;
        while (true) {
            List<Long> batch = reader.read(after, until, PageRequest.of(page++, RECONCILE_ID_BATCH_SIZE));
            if (batch.isEmpty()) break;
            ids.addAll(batch);
            if (batch.size() < RECONCILE_ID_BATCH_SIZE) break;
        }
        syncQueueService.enqueueAllForReconciliation(type, ids);
        log.debug("飞书增量对账: type={}, changed={}", type, ids.size());
    }

    private LocalDateTime parseCursor(String value) {
        try { return LocalDateTime.parse(value); }
        catch (Exception ignored) {
            log.warn("飞书同步游标无效，使用安全回溯时间: value={}", value);
            return SAFE_SYNC_CURSOR_FALLBACK;
        }
    }

    private void syncProject(Long projectId, boolean includeBackup) throws Exception {
        Project p = projectRepository.findByIdWithTasks(projectId)
                .or(() -> projectRepository.findById(projectId))
                .orElseThrow(() -> new Exception("项目不存在: " + projectId));

        int taskCount = p.getTasks() != null ? p.getTasks().size() : 0;
        int doneCount = p.getTasks() != null ?
                (int) p.getTasks().stream()
                        .filter(t -> List.of("approved", "completed", "sales_approved", "admin_approved").contains(t.getStatus()))
                        .count() : 0;
        int progress = taskCount > 0 ? (doneCount * 100 / taskCount) : 0;

        List<ScoringRecord> reviewRecords = scoringRepository.findByProjectIds(List.of(projectId));
        int expectedReviewCount = taskCount * 2;
        int approvedReviewCount = (int) reviewRecords.stream()
                .filter(this::isApprovedReview)
                .count();
        int reviewProgress = expectedReviewCount > 0
                ? approvedReviewCount * 100 / expectedReviewCount : 0;
        String projectFlow = projectFlowLabel(p.getStatus(), p.getWorkflowStage(), p.getWorkflowStatus());
        String currentReviewStage = currentReviewStage(p, reviewRecords);

        String categoryName = p.getProductCategory() != null ? p.getProductCategory().getName() : null;

        if (feishuBaseService.isV2Active()) {
            List<Long> taskIds = p.getTasks() == null ? List.of() : p.getTasks().stream().map(SubTask::getId).toList();
            feishuBaseService.syncV2Project(new FeishuBaseService.V2ProjectData(
                    p.getId(), p.getProjectCode(), p.getProductName(), p.getStatus(), p.getSalesName(),
                    p.getPlannerName(), categoryName, p.getPriceRange(), p.getReferenceImagesJson(),
                    p.getAttachmentsJson(), taskCount, p.getCreatedAt(), p.getDeadline(), p.getUpdatedAt(),
                    taskIds, taskProgressSummary(p.getTasks()), progress, projectFlowLabel(p.getStatus(),
                    p.getWorkflowStage(), p.getWorkflowStatus()), progress, p.getDescription()), includeBackup);
            return;
        }

        feishuBaseService.syncProject(
                p.getId(), p.getType(), p.getStatus(),
                p.getSalesName(), p.getPlannerName(),
                p.getDeadline(), categoryName,
                p.getPriceRange(), taskCount, progress,
                projectFlow, currentReviewStage, reviewProgress,
                projectExtraFields(p, taskProgressSummary(p.getTasks())),
                p.getCreatedAt()
        );
    }

    private void syncSubTask(Long taskId, boolean includeBackup) throws Exception {
        SubTask t = subTaskRepository.findByIdWithProject(taskId)
                .or(() -> subTaskRepository.findById(taskId))
                .orElseThrow(() -> new Exception("子任务不存在: " + taskId));

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
                    weighted += score * review.getWeight(); weights += review.getWeight();
                }
            }
            List<SubTaskDeliveryVersion> versions = deliveryVersionRepository == null ? List.of()
                    : deliveryVersionRepository.findBySubTaskIdOrderByVersionNoDesc(taskId);
            SubTaskDeliveryVersion latest = versions.isEmpty() ? null : versions.get(0);
            String deliveryImages = latest != null ? latest.getReferenceImagesJson() : t.getReferenceImagesJson();
            String deliveryFiles = latest != null ? latest.getAttachmentsJson() : t.getAttachmentsJson();
            double aggregate = weights > 0 ? Math.round(weighted / weights * 10d) / 10d : 0;
            feishuBaseService.syncV2Task(new FeishuBaseService.V2TaskData(
                    t.getId(), t.getProject() != null ? t.getProject().getId() : null, t.getName(), t.getStatus(),
                    latest == null ? t.getReferenceImagesJson() : null, latest == null ? t.getAttachmentsJson() : null,
                    t.getDesignerName(), t.getDetails(), t.getCreatedAt(), t.getPlannedDate(), t.getUpdatedAt(),
                    deliveryImages, deliveryFiles, t.getSelfScore(), roleScores.get("sales"), roleScores.get("planner"),
                    roleScores.get("admin"), weights > 0 ? aggregate : null, reviews.stream().map(ScoringRecord::getId).toList(),
                    workflowStageLabel(t.getWorkflowStage()), taskProgress(t.getStatus()), t.getReviewComments()), includeBackup);
            return;
        }

        String actualDate = t.getActualDate();
        if ((actualDate == null || actualDate.isBlank()) && deliveryVersionRepository != null) {
            actualDate = deliveryVersionRepository
                    .findFirstBySubTaskIdAndActualDateIsNotNullOrderByVersionNoDesc(taskId)
                    .map(SubTaskDeliveryVersion::getActualDate)
                    .filter(value -> !value.isBlank())
                    .orElse(null);
        }

        feishuBaseService.syncSubTask(new FeishuBaseService.SubTaskSyncData(
                t.getId(), t.getName(), t.getStatus(),
                t.getDesignerName(), t.getPlannedDate(),
                actualDate, t.getSelfScore(),
                t.getProject() != null ? t.getProject().getId() : null,
                reviewRole(firstReview, "planner"), reviewStatus(firstReview), reviewScore(firstReview), reviewName(firstReview),
                reviewRole(secondReview, "channel_custom".equals(projectType) ? "sales" : "admin"),
                reviewStatus(secondReview), reviewScore(secondReview), reviewName(secondReview),
                finalReviewScore(firstReview, secondReview),
                t.getCreatedAt(), subTaskExtraFields(t)
        ));
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
        String terminal = switch (projectStatus == null ? "" : projectStatus) {
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
            if (List.of("approved", "completed", "sales_approved", "admin_approved").contains(status)) completed++;
            else if (List.of("delivered", "submitted_for_review", "planner_approved", "scoring_planner").contains(status)) reviewing++;
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

    private void syncScoring(Long recordId, boolean includeBackup) throws Exception {
        ScoringRecord r = scoringRepository.findByIdWithTaskAndProject(recordId)
                .or(() -> scoringRepository.findById(recordId))
                .orElseThrow(() -> new Exception("评分记录不存在: " + recordId));

        SubTask task = r.getSubTask();
        Project project = task != null ? task.getProject() : null;
        if (feishuBaseService.isV2Active()) {
            Integer score = reviewScore(r);
            Double weighted = score == null ? null : score * (r.getWeight() == null ? 1d : r.getWeight());
            feishuBaseService.syncV2Scoring(new FeishuBaseService.V2ScoringData(
                    r.getId(), r.getRole(), r.getReviewerName(), task != null ? task.getId() : null,
                    r.getReviewedAt(), r.getWeight(), weighted, r.getComment()), includeBackup);
            return;
        }
        feishuBaseService.syncScoring(new FeishuBaseService.ScoringSyncData(
                r.getId(), r.getRole(), r.getScore(), r.getWeight(),
                task != null ? task.getId() : null,
                project != null ? project.getId() : null,
                project != null ? project.getType() : null,
                normalizedReviewStage(r), normalizedReviewStatus(r),
                r.getReviewerName(), r.getComment(), r.getReviewedAt()
        ));
    }

    private String currentReviewStage(Project project, List<ScoringRecord> reviews) {
        if (project.getTasks() == null || project.getTasks().isEmpty()) return "未进入审核";
        if (project.getTasks().stream().allMatch(t -> "completed".equals(t.getStatus()))) return "审核完成";
        if (reviews.stream().anyMatch(r -> "second".equals(normalizedReviewStage(r))
                && "rejected".equals(normalizedReviewStatus(r)))) return "二审已驳回";
        if (reviews.stream().anyMatch(r -> "first".equals(normalizedReviewStage(r))
                && "rejected".equals(normalizedReviewStatus(r)))) return "一审已驳回";
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
                        .findFirst().orElse(null));
    }

    private boolean isApprovedReview(ScoringRecord record) {
        return "approved".equals(normalizedReviewStatus(record));
    }

    private String normalizedReviewStatus(ScoringRecord record) {
        if (record == null) return "pending";
        if (record.getReviewStatus() != null && !record.getReviewStatus().isBlank()) {
            return record.getReviewStatus();
        }
        return record.getScore() != null
                || (record.getAesthetics() != null && record.getInnovation() != null)
                ? "approved" : "pending";
    }

    private String normalizedReviewStage(ScoringRecord record) {
        if (record != null && record.getReviewStage() != null && !record.getReviewStage().isBlank()) {
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
        if (!isApprovedReview(firstReview) || !isApprovedReview(secondReview)
                || firstScore == null || secondScore == null) {
            return null;
        }
        double firstWeight = firstReview.getWeight() != null ? firstReview.getWeight() : 1.0;
        double secondWeight = secondReview.getWeight() != null ? secondReview.getWeight() : 1.0;
        double totalWeight = firstWeight + secondWeight;
        if (totalWeight <= 0) return null;
        return Math.round(((firstScore * firstWeight + secondScore * secondWeight) / totalWeight) * 10.0) / 10.0;
    }

    private void syncActivityLog(Long logId, boolean includeBackup) throws Exception {
        ActivityLog log = activityLogRepository.findById(logId)
                .orElseThrow(() -> new Exception("操作日志不存在: " + logId));
        if (feishuBaseService.isV2Active()) {
            feishuBaseService.syncV2Log(new FeishuBaseService.V2LogData(log.getId(), log.getTime(), log.getRole(),
                    log.getUsername(), log.getAction(), log.getChangedFields()), includeBackup);
            return;
        }
        feishuBaseService.syncActivityLog(log.getId(), log.getAction(), log.getUsername(), log.getRole(),
                log.getProjectRefId(), log.getTime());
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

    private void deleteProject(Long projectId) throws Exception {
        // 级联删除关联的子任务和评分记录
        List<SubTask> tasks = subTaskRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        for (SubTask t : tasks) {
            List<ScoringRecord> scores = scoringRepository.findBySubTaskId(t.getId());
            for (ScoringRecord s : scores) {
                feishuBaseService.deleteScoringRecord(s.getId());
            }
            feishuBaseService.deleteSubTaskRecord(t.getId());
        }
        // 删除项目本身
        feishuBaseService.deleteProjectRecord(projectId);
        log.info("已删除飞书项目及关联数据: project={}, subTasks={}, scores={}",
                projectId, tasks.size(), tasks.stream().flatMap(t -> scoringRepository.findBySubTaskId(t.getId()).stream()).count());
    }

    private void deleteSubTask(Long taskId) throws Exception {
        feishuBaseService.deleteSubTaskRecord(taskId);
        log.info("已删除飞书子任务记录: {}", taskId);
    }

    private void deleteScoring(Long recordId) throws Exception {
        feishuBaseService.deleteScoringRecord(recordId);
        log.info("已删除飞书评分记录: {}", recordId);
    }
}
