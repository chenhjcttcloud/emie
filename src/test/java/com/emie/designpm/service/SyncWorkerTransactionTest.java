package com.emie.designpm.service;

import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.SyncQueue;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SyncWorker 按条目事务：processQueue 中每条队列任务在独立的主库短事务里执行
 * 「读业务数据 + 处理 + 保存队列状态」，避免整轮 20 条横跨单个长事务持有主库连接。
 * 旧 7 参构造器让 itemTransaction=null 走非事务路径，这里同样覆盖。
 */
class SyncWorkerTransactionTest {

    @Test
    void processQueueRunsEachItemInsideItsOwnShortTransaction() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        SyncQueue item1 = queueItem(1L);
        SyncQueue item2 = queueItem(2L);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("pending"), eq("pending"), any())).thenReturn(List.of(item1, item2));
        when(logs.findById(1L)).thenReturn(Optional.of(log(1L)));
        when(logs.findById(2L)).thenReturn(Optional.of(log(2L)));

        worker(queue, logs, feishu, txManager).processQueue();

        // 每条任务一个独立事务：getTransaction -> [处理] -> commit 逐条成对出现，
        // 每条任务的飞书处理必须落在各自事务边界之内。
        InOrder order = inOrder(txManager, feishu);
        order.verify(txManager).getTransaction(any());
        order.verify(feishu).syncActivityLog(eq(1L), anyString(), anyString(), anyString(), any(), any());
        order.verify(txManager).commit(any());
        order.verify(txManager).getTransaction(any());
        order.verify(feishu).syncActivityLog(eq(2L), anyString(), anyString(), anyString(), any(), any());
        order.verify(txManager).commit(any());

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
                eq("pending"), eq("pending"), any())).thenReturn(List.of(item));
        when(logs.findById(1L)).thenReturn(Optional.of(log(1L)));

        // 旧 7 参构造器：itemTransaction = null，走非事务路径但处理语义不变
        new SyncWorker(queue, mock(ProjectRepository.class), mock(SubTaskRepository.class),
                mock(ScoringRepository.class), logs, feishu, mock(SyncQueueService.class)).processQueue();

        verify(feishu).syncActivityLog(eq(1L), anyString(), anyString(), anyString(), any(), any());
        verify(queue, times(2)).saveQueue(item);
        assertEquals("done", item.getStatus());
    }

    @Test
    void recoverStuckItemsRunsOutsidePerItemTransactions() {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        SyncQueue stuck = SyncQueue.builder().entityType("project").entityId(9L)
                .action("update").status("processing").retryCount(0).build();
        when(queue.findByStatusAndUpdatedAtBefore(eq("processing"), any())).thenReturn(List.of(stuck));
        // 本轮没有新的可处理条目
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("pending"), eq("pending"), any())).thenReturn(List.of());

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
                eq("pending"), eq("pending"), any())).thenReturn(List.of(item1, item2));
        when(logs.findById(1L)).thenReturn(Optional.of(log(1L)));
        when(logs.findById(2L)).thenReturn(Optional.of(log(2L)));
        doThrow(new RuntimeException("boom")).when(feishu)
                .syncActivityLog(eq(1L), anyString(), anyString(), anyString(), any(), any());

        worker(queue, logs, feishu, txManager).processQueue();

        // 失败条目被单独落为 pending 等待重试，不污染同轮其它条目的独立事务
        assertEquals("pending", item1.getStatus());
        assertEquals(1, item1.getRetryCount());
        assertEquals("boom", item1.getErrorMsg());
        assertNotNull(item1.getNextRetryAt());
        assertEquals("done", item2.getStatus());
        verify(txManager, times(2)).getTransaction(any());
        verify(txManager, times(2)).commit(any());
        verify(txManager, never()).rollback(any());
    }

    private SyncWorker worker(SyncQueueOperations queue, ActivityLogRepository logs,
                              FeishuBaseService feishu, PlatformTransactionManager txManager) {
        return new SyncWorker(queue, mock(ProjectRepository.class), mock(SubTaskRepository.class),
                mock(ScoringRepository.class), logs, feishu, mock(SyncQueueService.class),
                mock(SystemConfigRepository.class), txManager);
    }

    private SyncQueue queueItem(long entityId) {
        return SyncQueue.builder().entityType("activity_log").entityId(entityId)
                .action("update").status("pending").retryCount(0).build();
    }

    private ActivityLog log(long id) {
        ActivityLog log = new ActivityLog("测试同步", "张三", "planner");
        log.setId(id);
        log.setTime(LocalDateTime.of(2025, 1, 1, 10, 0));
        log.setProjectRefId(1L);
        return log;
    }
}
