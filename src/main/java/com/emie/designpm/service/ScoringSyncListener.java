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
        enqueueAfterCommit("scoring_record", r.getId(), "update", "评分创建已入队");
    }
    @PostUpdate public void onUpdated(ScoringRecord r) {
        enqueueAfterCommit("scoring_record", r.getId(), "update", "评分更新已入队");
    }
    @PostRemove public void onDeleted(ScoringRecord r) {
        enqueueAfterCommit("scoring_record", r.getId(), "delete", "评分删除已入队");
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
