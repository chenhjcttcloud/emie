package com.emie.designpm.sync.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.feishu.service.FeishuBaseService;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.sync.repository.SyncQueueRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 2026-09-14 排查飞书「操作日志表」显示陈旧日期时定位到：{@code reconcileCurrentData} 是全量对账，
 * 不是增量——每小时把四张表的全部 ID 原样重新入队，不管有没有变化过。代码里其实已经写了一套
 * 增量方案（{@code enqueueUpdated} + {@code feishu.sync.cursor} 游标），但游标只写不读，
 * {@code enqueueUpdated}/{@code parseCursor} 全项目零调用，是死代码。下面两条测试把这个现状钉死，
 * 修复（真正接上游标过滤）之前先证明问题存在、修完之后也不会不知不觉又退回去。
 */
class SyncWorkerSchedulingTest {

    @Test
    void queueConsumerDoesNotTriggerFullReconciliation() {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(List.of());

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

        new SyncWorker(queue, projects, tasks, scorings, logs, feishu, queueService).reconcileCurrentData();

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
                .when(feishu)
                .reconcileMirrors(Set.of(), Set.of(), Set.of(), Set.of());

        Logger logger = (Logger) LoggerFactory.getLogger(SyncWorker.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        boolean originalAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(captured);
        try {
            new SyncWorker(queue, projects, tasks, scorings, logs, feishu, queueService).reconcileCurrentData();
        } finally {
            logger.detachAppender(captured);
            logger.setAdditive(originalAdditive);
            captured.stop();
        }

        verifyNoInteractions(queueService);
        assertTrue(captured.list.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("mirror unavailable")));
    }

    @Test
    void fullReconciliationReenqueuesEveryRecordRegardlessOfAgeOrPriorSync() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scorings = mock(ScoringRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        SystemConfigRepository configRepo = mock(SystemConfigRepository.class);
        when(queue.countByStatus("pending")).thenReturn(0L);
        when(queue.countByStatus("processing")).thenReturn(0L);
        when(projects.findIdsAfter(any(), any())).thenReturn(List.of());
        when(tasks.findIdsAfter(any(), any())).thenReturn(List.of());
        when(scorings.findIdsAfter(any(), any())).thenReturn(List.of());
        // 101-103 代表几天前已经成功同步过、早就没有变化的历史操作日志（对应生产上 9 月初的旧记录）。
        when(logs.findIdsAfter(any(), any())).thenReturn(List.of(101L, 102L, 103L));
        when(configRepo.findByConfigKey("feishu.sync.cursor")).thenReturn(Optional.empty());

        new SyncWorker(queue, projects, tasks, scorings, logs, feishu, queueService, configRepo, null)
                .reconcileCurrentData();

        // 现状：不看这些记录有没有变过，每一轮全量对账都把它们原样重新塞回队列，
        // 和真正的新记录抢同一条队——这正是生产上旧日志反复重新同步、队列锯齿式暴涨的原因。
        verify(queueService).enqueueAllForReconciliation("activity_log", List.of(101L, 102L, 103L));
    }

    @Test
    void reconciliationCursorIsPersistedButNeverReadBackToLimitTheNextRun() throws Exception {
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scorings = mock(ScoringRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        SyncQueueService queueService = mock(SyncQueueService.class);
        SystemConfigRepository configRepo = mock(SystemConfigRepository.class);
        when(queue.countByStatus("pending")).thenReturn(0L);
        when(queue.countByStatus("processing")).thenReturn(0L);
        when(projects.findIdsAfter(any(), any())).thenReturn(List.of());
        when(tasks.findIdsAfter(any(), any())).thenReturn(List.of());
        when(scorings.findIdsAfter(any(), any())).thenReturn(List.of());
        when(logs.findIdsAfter(any(), any())).thenReturn(List.of(101L));
        // 游标显示"5 分钟前才对账过"——如果游标真的被拿来做增量过滤，这一轮应该没有可同步的了。
        SystemConfig existingCursor = SystemConfig.builder()
                .configKey("feishu.sync.cursor")
                .configValue(LocalDateTime.now().minusMinutes(5).toString())
                .build();
        when(configRepo.findByConfigKey("feishu.sync.cursor")).thenReturn(Optional.of(existingCursor));

        new SyncWorker(queue, projects, tasks, scorings, logs, feishu, queueService, configRepo, null)
                .reconcileCurrentData();

        // 游标确实被更新覆盖了……
        verify(configRepo).save(existingCursor);
        // ……但对本轮查了哪些 ID、入队了哪些记录毫无影响：跟游标是 5 分钟前还是从来没有过一样，
        // 结果都是把 findIdsAfter 返回的全部记录重新入队。游标形同虚设。
        verify(queueService).enqueueAllForReconciliation("activity_log", List.of(101L));
    }

    private SyncWorker worker(SyncQueueRepository queue, SyncQueueService queueService) {
        return new SyncWorker(
                queue,
                mock(ProjectRepository.class),
                mock(SubTaskRepository.class),
                mock(ScoringRepository.class),
                mock(ActivityLogRepository.class),
                mock(FeishuBaseService.class),
                queueService);
    }
}
