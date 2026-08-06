package com.emie.designpm.service;

import com.emie.designpm.entity.SyncQueue;
import com.emie.designpm.repository.SyncQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SyncQueueService {

    private static final Logger log = LoggerFactory.getLogger(SyncQueueService.class);
    private static final List<String> ACTIVE_STATUSES = List.of("pending", "processing");
    private final SyncQueueRepository syncQueueRepository;

    public SyncQueueService(SyncQueueRepository syncQueueRepository) {
        this.syncQueueRepository = syncQueueRepository;
    }

    /** 入队 */
    @Transactional
    public void enqueue(String entityType, Long entityId, String action) {
        String result = enqueueDeduplicated(entityType, entityId, action);
        if (!"skipped".equals(result)) {
            log.debug("同步入队: {} {} {} ({})", entityType, entityId, action, result);
        }
    }

    private String enqueueDeduplicated(String entityType, Long entityId, String action) {
        List<SyncQueue> activeItems = syncQueueRepository.findByEntityTypeAndEntityIdAndStatusIn(
                entityType, entityId, ACTIVE_STATUSES);

        if ("delete".equals(action)) {
            Optional<SyncQueue> existingDelete = activeItems.stream()
                    .filter(item -> "delete".equals(item.getAction()))
                    .findFirst();
            if (existingDelete.isPresent()) {
                return "skipped";
            }
            if (!activeItems.isEmpty()) {
                SyncQueue existing = activeItems.get(0);
                existing.setAction("delete");
                existing.setStatus("pending");
                existing.setRetryCount(0);
                existing.setErrorMsg(null);
                existing.setNextRetryAt(null);
                syncQueueRepository.save(existing);
                return "updated";
            }
        } else {
            boolean hasDelete = activeItems.stream().anyMatch(item -> "delete".equals(item.getAction()));
            if (hasDelete || !activeItems.isEmpty()) {
                return "skipped";
            }
        }

        // 失败任务应在业务记录再次变更或全量重刷时复用并重置，避免不断产生重复失败记录。
        Optional<SyncQueue> failed = syncQueueRepository
                .findFirstByEntityTypeAndEntityIdAndStatusOrderByCreatedAtDesc(entityType, entityId, "fail");
        if (failed.isPresent()) {
            SyncQueue item = failed.get();
            item.setAction(action);
            item.setStatus("pending");
            item.setRetryCount(0);
            item.setErrorMsg(null);
            item.setNextRetryAt(null);
            syncQueueRepository.save(item);
            return "updated";
        }

        SyncQueue item = SyncQueue.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .status("pending")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        syncQueueRepository.save(item);
        return "added";
    }

    /** 批量入队（全量同步用） */
    @Transactional
    public Map<String, Integer> enqueueAll(String entityType, List<Long> ids) {
        int added = 0;
        int updated = 0;
        int skipped = 0;
        for (Long id : ids) {
            String result = enqueueDeduplicated(entityType, id, "update");
            switch (result) {
                case "added" -> added++;
                case "updated" -> updated++;
                default -> skipped++;
            }
        }
        log.info("批量入队: {} total={} added={} updated={} skipped={}", entityType, ids.size(), added, updated, skipped);
        return Map.of("total", ids.size(), "added", added, "updated", updated, "skipped", skipped);
    }

    /** 周期性对账：重新激活当前业务记录对应的已完成任务，补回飞书端被删除的记录。 */
    @Transactional
    public Map<String, Integer> enqueueAllForReconciliation(String entityType, List<Long> ids) {
        int requeued = 0;
        int added = 0;
        int skipped = 0;
        for (Long id : ids) {
            List<SyncQueue> active = syncQueueRepository.findByEntityTypeAndEntityIdAndStatusIn(
                    entityType, id, ACTIVE_STATUSES);
            if (!active.isEmpty()) {
                skipped++;
                continue;
            }

            List<SyncQueue> completed = syncQueueRepository.findByEntityTypeAndEntityIdAndStatusIn(
                    entityType, id, List.of("done"));
            if (!completed.isEmpty()) {
                SyncQueue item = completed.get(completed.size() - 1);
                item.setStatus("pending");
                item.setRetryCount(0);
                item.setErrorMsg(null);
                item.setNextRetryAt(null);
                syncQueueRepository.save(item);
                requeued++;
            } else {
                enqueueDeduplicated(entityType, id, "update");
                added++;
            }
        }
        return Map.of("total", ids.size(), "requeued", requeued, "added", added, "skipped", skipped);
    }

    /** 获取待处理队列 */
    public List<SyncQueue> getPending() {
        return syncQueueRepository.findTop20ByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                "pending", LocalDateTime.now());
    }

    /** 统计 */
    public Map<String, Object> getStats() {
        long pending = syncQueueRepository.countByStatus("pending");
        long done = syncQueueRepository.countByStatus("done");
        long fail = syncQueueRepository.countByStatus("fail");
        long failRetry = syncQueueRepository.countByStatusAndRetryCountGreaterThanEqual("fail", 3);
        Optional<SyncQueue> latestDone = syncQueueRepository.findTopByStatusOrderByUpdatedAtDesc("done");
        Optional<SyncQueue> latestFail = syncQueueRepository.findTopByStatusOrderByUpdatedAtDesc("fail");
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("pending", pending);
        stats.put("processing", syncQueueRepository.countByStatus("processing"));
        stats.put("done", done);
        stats.put("fail", fail);
        stats.put("failRetryExceeded", failRetry);
        stats.put("lastSuccessAt", latestDone.map(SyncQueue::getUpdatedAt).orElse(null));
        stats.put("lastFailureAt", latestFail.map(SyncQueue::getUpdatedAt).orElse(null));
        stats.put("lastFailure", latestFail.map(item -> Map.of(
                "id", item.getId(),
                "entityType", item.getEntityType(),
                "entityId", item.getEntityId(),
                "retryCount", item.getRetryCount() == null ? 0 : item.getRetryCount(),
                "error", item.getErrorMsg() == null ? "未知错误" : item.getErrorMsg()
        )).orElse(null));
        return stats;
    }
}
