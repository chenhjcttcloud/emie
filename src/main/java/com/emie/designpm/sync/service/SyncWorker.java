package com.emie.designpm.sync.service;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.feishu.service.FeishuBaseService;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskDeliveryVersionRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.sync.repository.SyncQueueOperations;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final SyncPayloadFactory payloadFactory;
    /** 只用于读取同步快照，绝不包含飞书 HTTP 调用。 */
    private final TransactionTemplate itemTransaction;

    @Autowired
    public SyncWorker(
            @Qualifier("backgroundSyncQueueRepository") SyncQueueOperations syncQueueRepository,
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
        this.itemTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (itemTransaction != null) itemTransaction.setReadOnly(true);
        this.payloadFactory = new SyncPayloadFactory(
                projectRepository,
                subTaskRepository,
                scoringRepository,
                activityLogRepository,
                feishuBaseService,
                deliveryVersionRepository);
    }

    /** 保留单元测试和旧调用方的构造签名；生产由 Spring 注入配置仓库。 */
    public SyncWorker(
            SyncQueueOperations syncQueueRepository,
            ProjectRepository projectRepository,
            SubTaskRepository subTaskRepository,
            ScoringRepository scoringRepository,
            ActivityLogRepository activityLogRepository,
            FeishuBaseService feishuBaseService,
            SyncQueueService syncQueueService) {
        this(
                syncQueueRepository,
                projectRepository,
                subTaskRepository,
                scoringRepository,
                activityLogRepository,
                feishuBaseService,
                syncQueueService,
                null,
                null,
                null);
    }

    SyncWorker(
            SyncQueueOperations syncQueueRepository,
            ProjectRepository projectRepository,
            SubTaskRepository subTaskRepository,
            ScoringRepository scoringRepository,
            ActivityLogRepository activityLogRepository,
            FeishuBaseService feishuBaseService,
            SyncQueueService syncQueueService,
            SystemConfigRepository systemConfigRepository,
            PlatformTransactionManager transactionManager) {
        this(
                syncQueueRepository,
                projectRepository,
                subTaskRepository,
                scoringRepository,
                activityLogRepository,
                feishuBaseService,
                syncQueueService,
                systemConfigRepository,
                null,
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
        List<SyncQueue> items =
                syncQueueRepository
                        .findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                                "pending", "pending", LocalDateTime.now());
        if (items.isEmpty()) return;

        log.info("飞书同步队列: {} 条待处理", items.size());

        for (SyncQueue item : items) {
            processSingleItem(item);
        }
    }

    private void processSingleItem(SyncQueue item) {
        try {
            item.setStatus("processing");
            syncQueueRepository.saveQueue(item);

            SyncPayloadFactory.Action action = itemTransaction == null
                    ? payloadFactory.prepare(item)
                    : itemTransaction.execute(status -> payloadFactory.prepare(item));
            Objects.requireNonNull(action).run();

            item.setStatus("done");
            item.setErrorMsg(null);
            item.setNextRetryAt(null);
            log.debug("同步成功: {} {}", item.getEntityType(), item.getEntityId());

        } catch (Exception e) {
            item.setRetryCount(item.getRetryCount() + 1);
            item.setErrorMsg(
                    e.getMessage() != null
                            ? e.getMessage()
                                    .substring(0, Math.min(500, e.getMessage().length()))
                            : "未知错误");
            if (item.getRetryCount() >= 3) {
                item.setStatus("fail");
                log.warn("同步失败(已重试3次): {} {} - {}", item.getEntityType(), item.getEntityId(), e.getMessage());
            } else {
                item.setStatus("pending");
                item.setNextRetryAt(
                        LocalDateTime.now().plusSeconds(Math.min(900, 30L << Math.min(item.getRetryCount(), 4))));
                log.warn(
                        "同步失败(将重试 {}/3): {} {} - {}",
                        item.getRetryCount(),
                        item.getEntityType(),
                        item.getEntityId(),
                        e.getMessage());
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
            initialDelayString = "${app.feishu.reconcile-initial-delay-ms:300000}")
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
        if (syncQueueRepository.countByStatus("pending") > 0 || syncQueueRepository.countByStatus("processing") > 0) {
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
                    .mapToInt(FeishuBaseService.MirrorReconcileResult::skippedWithoutBackup)
                    .sum();
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
            SystemConfig cursor = systemConfigRepository
                    .findByConfigKey("feishu.sync.cursor")
                    .orElseGet(() -> SystemConfig.builder()
                            .configKey("feishu.sync.cursor")
                            .configGroup("system")
                            .valueType("text")
                            .description("飞书增量同步游标")
                            .build());
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
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            log.warn("飞书同步游标无效，使用安全回溯时间: value={}", value);
            return SAFE_SYNC_CURSOR_FALLBACK;
        }
    }

    static String latestDeliveryActualDate(List<SubTaskDeliveryVersion> versions) {
        return SyncPayloadFactory.latestDeliveryActualDate(versions);
    }

    static String projectFlowLabel(String status, String stage, String workflowStatus) {
        return SyncPayloadFactory.projectFlowLabel(status, stage, workflowStatus);
    }

    static String taskProgressSummary(List<SubTask> tasks) {
        return SyncPayloadFactory.taskProgressSummary(tasks);
    }
}
