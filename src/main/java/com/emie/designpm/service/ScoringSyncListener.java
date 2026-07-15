package com.emie.designpm.service;

import com.emie.designpm.entity.ScoringRecord;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ScoringSyncListener implements ApplicationContextAware {
    private static final Logger log = LoggerFactory.getLogger(ScoringSyncListener.class);
    private static SyncQueueService syncQueueService;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        syncQueueService = ctx.getBean(SyncQueueService.class);
    }

    @PostPersist public void onCreated(ScoringRecord r) {
        enqueueReviewChange(r, "update", "评分创建已入队");
    }
    @PostUpdate public void onUpdated(ScoringRecord r) {
        enqueueReviewChange(r, "update", "评分更新已入队");
    }
    @PostRemove public void onDeleted(ScoringRecord r) {
        enqueueReviewChange(r, "delete", "评分删除已入队");
    }

    private void enqueueReviewChange(ScoringRecord record, String scoringAction, String logMessage) {
        enqueueAfterCommit("scoring_record", record.getId(), scoringAction, logMessage);
        if (record.getSubTask() == null) return;
        enqueueAfterCommit("sub_task", record.getSubTask().getId(), "update", "审核汇总子任务已入队");
        if (record.getSubTask().getProject() != null) {
            enqueueAfterCommit("project", record.getSubTask().getProject().getId(), "update", "审核进度项目已入队");
        }
    }

    private void enqueueAfterCommit(String entityType, Long entityId, String action, String logMessage) {
        if (entityId == null || syncQueueService == null) {
            return;
        }
        Runnable enqueueTask = () -> {
            syncQueueService.enqueue(entityType, entityId, action);
            log.debug("{}: {}", logMessage, entityId);
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueueTask.run();
                }
            });
        } else {
            enqueueTask.run();
        }
    }
}
