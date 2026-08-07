package com.emie.designpm.service;

import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.repository.SyncQueueRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

class SyncWorkerSchedulingTest {

    @Test
    void queueConsumerDoesNotTriggerFullReconciliation() {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("pending"), eq("pending"), any())).thenReturn(List.of());

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

    @Test
    void reconciliationCleansMirrorsBeforeQueueingCurrentRows() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scorings = mock(ScoringRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        when(queue.countByStatus("pending")).thenReturn(0L);
        when(projects.findAll()).thenReturn(List.of());
        when(tasks.findAll()).thenReturn(List.of());
        when(scorings.findAll()).thenReturn(List.of());
        when(logs.findAll()).thenReturn(List.of());

        new SyncWorker(queue, projects, tasks, scorings, logs, feishu, queueService)
                .reconcileCurrentData();

        verify(feishu).reconcileMirrors(Set.of(), Set.of(), Set.of(), Set.of());
        verify(queueService).enqueueAllForReconciliation("project", List.of());
        verify(queueService).enqueueAllForReconciliation("sub_task", List.of());
        verify(queueService).enqueueAllForReconciliation("scoring_record", List.of());
        verify(queueService).enqueueAllForReconciliation("activity_log", List.of());
    }

    @Test
    void reconciliationDoesNotQueueWritesWhenMirrorCleanupFails() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scorings = mock(ScoringRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        when(queue.countByStatus("pending")).thenReturn(0L);
        when(projects.findAll()).thenReturn(List.of());
        when(tasks.findAll()).thenReturn(List.of());
        when(scorings.findAll()).thenReturn(List.of());
        when(logs.findAll()).thenReturn(List.of());
        doThrow(new IllegalStateException("mirror unavailable"))
                .when(feishu).reconcileMirrors(Set.of(), Set.of(), Set.of(), Set.of());

        new SyncWorker(queue, projects, tasks, scorings, logs, feishu, queueService)
                .reconcileCurrentData();

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
