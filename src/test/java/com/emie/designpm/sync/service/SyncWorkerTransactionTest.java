package com.emie.designpm.sync.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.SyncQueue;
import com.emie.designpm.feishu.service.FeishuBaseService;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.sync.repository.SyncQueueOperations;
import com.emie.designpm.sync.repository.SyncQueueRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** 读取快照必须先提交，飞书请求和队列状态写入均在读取事务之外执行。 */
class SyncWorkerTransactionTest {

    @Test
    void processQueueCommitsEachSnapshotBeforeCallingFeishu() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        SyncQueue item1 = queueItem(1L);
        SyncQueue item2 = queueItem(2L);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of(item1, item2));
        when(logs.findById(1L)).thenReturn(Optional.of(log(1L)));
        when(logs.findById(2L)).thenReturn(Optional.of(log(2L)));

        worker(queue, logs, feishu, txManager).processQueue();

        InOrder order = inOrder(txManager, feishu, logs);
        order.verify(txManager).getTransaction(any());
        order.verify(logs).findById(1L);
        order.verify(txManager).commit(any());
        order.verify(feishu).syncActivityLog(eq(1L), anyString(), anyString(), anyString(), any(), any());
        order.verify(txManager).getTransaction(any());
        order.verify(logs).findById(2L);
        order.verify(txManager).commit(any());
        order.verify(feishu).syncActivityLog(eq(2L), anyString(), anyString(), anyString(), any(), any());

        verify(txManager, times(2)).getTransaction(any());
        verify(txManager, times(2)).commit(any());
        verify(txManager, never()).rollback(any());
        // 每条 2 次状态保存（processing + done），共 4 次
        verify(queue, times(4)).saveQueue(any(SyncQueue.class));
        verify(feishu).syncActivityLog(eq(1L), anyString(), anyString(), anyString(), any(), any());
        verify(feishu).syncActivityLog(eq(2L), anyString(), anyString(), anyString(), any(), any());
        assertEquals("done", item1.getStatus());
        assertEquals("done", item2.getStatus());
    }

    @Test
    void legacySevenArgConstructorProcessesItemsWithoutPerItemTransaction() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);

        SyncQueue item = queueItem(1L);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of(item));
        when(logs.findById(1L)).thenReturn(Optional.of(log(1L)));

        // 旧 7 参构造器：itemTransaction = null，走非事务路径但处理语义不变
        new SyncWorker(
                        queue,
                        mock(ProjectRepository.class),
                        mock(SubTaskRepository.class),
                        mock(ScoringRepository.class),
                        logs,
                        feishu,
                        mock(SyncQueueService.class))
                .processQueue();

        verify(feishu).syncActivityLog(eq(1L), anyString(), anyString(), anyString(), any(), any());
        verify(queue, times(2)).saveQueue(item);
        assertEquals("done", item.getStatus());
    }

    @Test
    void recoverStuckItemsRunsOutsidePerItemTransactions() {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        SyncQueue stuck = SyncQueue.builder()
                .entityType("project")
                .entityId(9L)
                .action("update")
                .status("processing")
                .retryCount(0)
                .build();
        when(queue.findByStatusAndUpdatedAtBefore(eq("processing"), any())).thenReturn(List.of(stuck));
        // 本轮没有新的可处理条目
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of());

        worker(queue, mock(ActivityLogRepository.class), mock(FeishuBaseService.class), txManager)
                .processQueue();

        verify(queue).saveQueue(stuck);
        assertEquals("pending", stuck.getStatus());
        assertEquals("同步进程中断，已自动恢复", stuck.getErrorMsg());
        // 恢复路径不挂任何条目事务
        verifyNoInteractions(txManager);
    }

    @Test
    void oneFailingItemDoesNotAbortOrShareTransactionsWithOtherItems() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        SyncQueue item1 = queueItem(1L);
        SyncQueue item2 = queueItem(2L);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of(item1, item2));
        when(logs.findById(1L)).thenReturn(Optional.of(log(1L)));
        when(logs.findById(2L)).thenReturn(Optional.of(log(2L)));
        doThrow(new RuntimeException("boom"))
                .when(feishu)
                .syncActivityLog(eq(1L), anyString(), anyString(), anyString(), any(), any());

        worker(queue, logs, feishu, txManager).processQueue();

        // 外部调用失败发生在读取事务提交之后，仍沿用原先的队列重试。
        assertEquals("pending", item1.getStatus());
        assertEquals(1, item1.getRetryCount());
        assertEquals("boom", item1.getErrorMsg());
        assertNotNull(item1.getNextRetryAt());
        assertEquals("done", item2.getStatus());
        verify(txManager, times(2)).getTransaction(any());
        verify(txManager, times(2)).commit(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void failedSnapshotRollsBackAndNextItemStillRuns() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        SyncQueue missing = queueItem(1L);
        SyncQueue valid = queueItem(2L);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of(missing, valid));
        when(logs.findById(2L)).thenReturn(Optional.of(log(2L)));
        worker(queue, logs, feishu, manager).processQueue();
        assertEquals("pending", missing.getStatus());
        assertEquals(1, missing.getRetryCount());
        assertEquals("done", valid.getStatus());
        verify(manager).rollback(any());
        verify(manager).commit(any());
        verify(feishu, never()).syncActivityLog(eq(1L), any(), any(), any(), any(), any());
        verify(feishu).syncActivityLog(eq(2L), any(), any(), any(), any(), any());
    }

    @Test
    void failedCommitNeverSendsUncommittedSnapshot() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doThrow(new RuntimeException("commit failed")).when(manager).commit(any());
        SyncQueue item = queueItem(1L);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of(item));
        when(logs.findById(1L)).thenReturn(Optional.of(log(1L)));
        worker(queue, logs, feishu, manager).processQueue();
        assertEquals("pending", item.getStatus());
        assertEquals("commit failed", item.getErrorMsg());
        verify(feishu, never()).syncActivityLog(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cascadeDeleteReadsIdentifiersOnceAndCommitsBeforeRemoteDeletes() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scores = mock(ScoringRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        SyncQueue item = queueItem(1L);
        item.setEntityType("project");
        item.setAction("delete");
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of(item));
        com.emie.designpm.entity.SubTask task = new com.emie.designpm.entity.SubTask();
        task.setId(2L);
        com.emie.designpm.entity.ScoringRecord score = new com.emie.designpm.entity.ScoringRecord();
        score.setId(3L);
        when(tasks.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(task));
        when(scores.findBySubTaskId(2L)).thenReturn(List.of(score));
        new SyncWorker(
                        queue,
                        mock(ProjectRepository.class),
                        tasks,
                        scores,
                        mock(ActivityLogRepository.class),
                        feishu,
                        mock(SyncQueueService.class),
                        null,
                        manager)
                .processQueue();
        InOrder order = inOrder(manager, tasks, scores, feishu);
        order.verify(manager).getTransaction(any());
        order.verify(tasks).findByProjectIdOrderByCreatedAtAsc(1L);
        order.verify(scores).findBySubTaskId(2L);
        order.verify(manager).commit(any());
        order.verify(feishu).deleteScoringRecord(3L);
        order.verify(feishu).deleteSubTaskRecord(2L);
        order.verify(feishu).deleteProjectRecord(1L);
        verify(scores, times(1)).findBySubTaskId(2L);
        assertEquals("done", item.getStatus());
    }

    private SyncWorker worker(
            SyncQueueOperations queue,
            ActivityLogRepository logs,
            FeishuBaseService feishu,
            PlatformTransactionManager txManager) {
        return new SyncWorker(
                queue,
                mock(ProjectRepository.class),
                mock(SubTaskRepository.class),
                mock(ScoringRepository.class),
                logs,
                feishu,
                mock(SyncQueueService.class),
                mock(SystemConfigRepository.class),
                txManager);
    }

    private SyncQueue queueItem(long entityId) {
        return SyncQueue.builder()
                .entityType("activity_log")
                .entityId(entityId)
                .action("update")
                .status("pending")
                .retryCount(0)
                .build();
    }

    private ActivityLog log(long id) {
        ActivityLog log = new ActivityLog("测试同步", "张三", "planner");
        log.setId(id);
        log.setTime(LocalDateTime.of(2025, 1, 1, 10, 0));
        log.setProjectRefId(1L);
        return log;
    }
}
