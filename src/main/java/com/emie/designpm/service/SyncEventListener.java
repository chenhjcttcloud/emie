package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听实体变更事件，自动推送到飞书同步队列
 * 不影响现有业务逻辑，零侵入
 */
@Component
public class SyncEventListener {

    private static final Logger log = LoggerFactory.getLogger(SyncEventListener.class);
    private final SyncQueueService syncQueueService;

    public SyncEventListener(SyncQueueService syncQueueService) {
        this.syncQueueService = syncQueueService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectSaved(Project project) {
        if (project.getId() != null) {
            syncQueueService.enqueue("project", project.getId(), "update");
            log.debug("项目变更已入队: id={}", project.getId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubTaskSaved(SubTask task) {
        if (task.getId() != null) {
            syncQueueService.enqueue("sub_task", task.getId(), "update");
            log.debug("子任务变更已入队: id={}", task.getId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScoringSaved(ScoringRecord record) {
        if (record.getId() != null) {
            syncQueueService.enqueue("scoring_record", record.getId(), "update");
            log.debug("评分变更已入队: id={}", record.getId());
        }
    }
}
