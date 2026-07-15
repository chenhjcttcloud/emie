package com.emie.designpm.service;

import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.repository.SyncQueueRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class SyncWorkerSchedulingTest {

    @Test
    void queueConsumerDoesNotTriggerFullReconciliation() {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        when(queue.findTop20ByStatusOrderByCreatedAtAsc("pending")).thenReturn(List.of());

        worker(queue, queueService).processQueue();

        verifyNoInteractions(queueService);
    }

    @Test
    void reconciliationSkipsWhileQueueHasPendingWork() {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        when(queue.countByStatus("pending")).thenReturn(1L);

        worker(queue, queueService).reconcileCurrentData();

        verifyNoInteractions(queueService);
    }

    private SyncWorker worker(SyncQueueRepository queue, SyncQueueService queueService) {
        return new SyncWorker(
                queue,
                mock(ProjectRepository.class),
                mock(SubTaskRepository.class),
                mock(ScoringRepository.class),
                mock(ActivityLogRepository.class),
                mock(FeishuBaseService.class),
                queueService
        );
    }
}
