package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    private final SyncQueueRepository syncQueueRepository;
    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;
    private final FeishuBaseService feishuBaseService;
    private final SyncQueueService syncQueueService;
    private final SystemConfigRepository systemConfigRepository;

    @Autowired
    public SyncWorker(SyncQueueRepository syncQueueRepository,
                      ProjectRepository projectRepository,
                      SubTaskRepository subTaskRepository,
                      ScoringRepository scoringRepository,
                      ActivityLogRepository activityLogRepository,
                      FeishuBaseService feishuBaseService,
                      SyncQueueService syncQueueService,
                      SystemConfigRepository systemConfigRepository) {
        this.syncQueueRepository = syncQueueRepository;
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
        this.feishuBaseService = feishuBaseService;
        this.syncQueueService = syncQueueService;
        this.systemConfigRepository = systemConfigRepository;
    }

    /** 保留单元测试和旧调用方的构造签名；生产由 Spring 注入配置仓库。 */
    public SyncWorker(SyncQueueRepository syncQueueRepository,
                      ProjectRepository projectRepository,
                      SubTaskRepository subTaskRepository,
                      ScoringRepository scoringRepository,
                      ActivityLogRepository activityLogRepository,
                      FeishuBaseService feishuBaseService,
                      SyncQueueService syncQueueService) {
        this(syncQueueRepository, projectRepository, subTaskRepository, scoringRepository,
                activityLogRepository, feishuBaseService, syncQueueService, null);
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
        List<SyncQueue> items = syncQueueRepository.findTop20ByStatusOrderByCreatedAtAsc("pending");
        if (items.isEmpty()) return;

        log.info("飞书同步队列: {} 条待处理", items.size());

        for (SyncQueue item : items) {
            try {
                item.setStatus("processing");
                syncQueueRepository.save(item);

                switch (item.getEntityType()) {
                    case "project" -> {
                        if ("delete".equals(item.getAction())) {
                            deleteProject(item.getEntityId());
                        } else {
                            syncProject(item.getEntityId());
                        }
                    }
                    case "sub_task" -> {
                        if ("delete".equals(item.getAction())) {
                            deleteSubTask(item.getEntityId());
                        } else {
                            syncSubTask(item.getEntityId());
                        }
                    }
                    case "scoring_record" -> {
                        if ("delete".equals(item.getAction())) {
                            deleteScoring(item.getEntityId());
                        } else {
                            syncScoring(item.getEntityId());
                        }
                    }
                    case "activity_log" -> syncActivityLog(item.getEntityId());
                    default -> log.warn("未知同步类型: {}", item.getEntityType());
                }

                item.setStatus("done");
                item.setErrorMsg(null);
                log.debug("同步成功: {} {}", item.getEntityType(), item.getEntityId());

            } catch (Exception e) {
                item.setRetryCount(item.getRetryCount() + 1);
                item.setErrorMsg(e.getMessage() != null ? e.getMessage().substring(0, Math.min(500, e.getMessage().length())) : "未知错误");
                if (item.getRetryCount() >= 3) {
                    item.setStatus("fail");
                    log.warn("同步失败(已重试3次): {} {} - {}", item.getEntityType(), item.getEntityId(), e.getMessage());
                } else {
                    item.setStatus("pending");
                    log.warn("同步失败(将重试 {}/3): {} {} - {}", item.getRetryCount(), item.getEntityType(), item.getEntityId(), e.getMessage());
                }
            } finally {
                syncQueueRepository.save(item);
            }
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
        LocalDateTime after = systemConfigRepository == null ? SAFE_SYNC_CURSOR_FALLBACK
                : systemConfigRepository.findByConfigKey("feishu.sync.cursor")
                .map(SystemConfig::getConfigValue).map(this::parseCursor).orElse(SAFE_SYNC_CURSOR_FALLBACK);
        enqueueUpdated("project", projectRepository::findIdsUpdatedBetween, after, until);
        enqueueUpdated("sub_task", subTaskRepository::findIdsUpdatedBetween, after, until);
        enqueueUpdated("scoring_record", scoringRepository::findIdsUpdatedBetween, after, until);
        enqueueUpdated("activity_log", activityLogRepository::findIdsUpdatedBetween, after, until);
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

    private void syncProject(Long projectId) throws Exception {
        Project p = projectRepository.findById(projectId)
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
        String reviewFlow = "channel_custom".equals(p.getType())
                ? "产品企划一审 → 销售二审"
                : "产品企划一审 → 管理员二审";
        String currentReviewStage = currentReviewStage(p, reviewRecords);

        String categoryName = p.getProductCategory() != null ? p.getProductCategory().getName() : null;

        feishuBaseService.syncProject(
                p.getId(), p.getType(), p.getStatus(),
                p.getSalesName(), p.getPlannerName(),
                p.getDeadline(), categoryName,
                p.getPriceRange(), taskCount, progress,
                reviewFlow, currentReviewStage, reviewProgress,
                p.getCreatedAt()
        );
    }

    private void syncSubTask(Long taskId) throws Exception {
        SubTask t = subTaskRepository.findById(taskId)
                .orElseThrow(() -> new Exception("子任务不存在: " + taskId));

        List<ScoringRecord> reviews = scoringRepository.findBySubTaskId(taskId);
        ScoringRecord firstReview = findReview(reviews, "first");
        ScoringRecord secondReview = findReview(reviews, "second");
        String projectType = t.getProject() != null ? t.getProject().getType() : "regular";

        feishuBaseService.syncSubTask(new FeishuBaseService.SubTaskSyncData(
                t.getId(), t.getName(), t.getStatus(),
                t.getDesignerName(), t.getPlannedDate(),
                t.getActualDate(), t.getSelfScore(),
                t.getProject() != null ? t.getProject().getId() : null,
                reviewRole(firstReview, "planner"), reviewStatus(firstReview), reviewScore(firstReview), reviewName(firstReview),
                reviewRole(secondReview, "channel_custom".equals(projectType) ? "sales" : "admin"),
                reviewStatus(secondReview), reviewScore(secondReview), reviewName(secondReview),
                finalReviewScore(firstReview, secondReview),
                t.getCreatedAt()
        ));
    }

    private void syncScoring(Long recordId) throws Exception {
        ScoringRecord r = scoringRepository.findById(recordId)
                .orElseThrow(() -> new Exception("评分记录不存在: " + recordId));

        SubTask task = r.getSubTask();
        Project project = task != null ? task.getProject() : null;
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

    private void syncActivityLog(Long logId) throws Exception {
        ActivityLog log = activityLogRepository.findById(logId)
                .orElseThrow(() -> new Exception("操作日志不存在: " + logId));
        feishuBaseService.syncActivityLog(log.getId(), log.getAction(), log.getUsername(), log.getRole(),
                log.getProjectRefId(), log.getTime());
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
