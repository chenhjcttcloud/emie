package com.emie.designpm.controller;

import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.FeishuBaseService;
import com.emie.designpm.service.SyncQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class FeishuSyncControllerTest {

    @Test
    void fullResyncStopsBeforeQueueingWhenBackupPreflightFails() throws Exception {
        SyncQueueService queueService = mock(SyncQueueService.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        when(feishu.validateBackupTables()).thenReturn(Map.of("valid", false, "message", "备份表不可访问"));

        FeishuSyncController controller = controller(queueService, feishu);

        var response = controller.fullResync();

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        verifyNoInteractions(queueService);
    }

    @Test
    void fullResyncQueuesDataAfterBackupPreflightPasses() throws Exception {
        SyncQueueService queueService = mock(SyncQueueService.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        when(feishu.validateBackupTables()).thenReturn(Map.of("valid", true, "tables", List.of()));
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        ActivityLogRepository logs = mock(ActivityLogRepository.class);
        when(projects.findAll()).thenReturn(List.of());
        when(tasks.findAll()).thenReturn(List.of());
        when(scoring.findAll()).thenReturn(List.of());
        when(logs.findAll()).thenReturn(List.of());
        when(queueService.enqueueAll(anyString(), anyList())).thenReturn(Map.of("total", 0, "added", 0, "updated", 0, "skipped", 0));

        FeishuSyncController controller = new FeishuSyncController(queueService, feishu, projects, tasks, scoring, logs);

        var response = controller.fullResync();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(feishu).reconcileMirrors(Set.of(), Set.of(), Set.of(), Set.of());
        verify(queueService).enqueueAll("project", List.of());
        verify(queueService).enqueueAll("sub_task", List.of());
        verify(queueService).enqueueAll("scoring_record", List.of());
        verify(queueService).enqueueAll("activity_log", List.of());
    }

    @Test
    void fullResyncStopsBeforeQueueingWhenMirrorCleanupFails() throws Exception {
        SyncQueueService queueService = mock(SyncQueueService.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);
        when(feishu.validateBackupTables()).thenReturn(Map.of("valid", true, "tables", List.of()));
        doThrow(new Exception("飞书不可用"))
                .when(feishu).reconcileMirrors(Set.of(), Set.of(), Set.of(), Set.of());

        FeishuSyncController controller = controller(queueService, feishu);
        var response = controller.fullResync();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        verifyNoInteractions(queueService);
    }

    private FeishuSyncController controller(SyncQueueService queueService, FeishuBaseService feishu) {
        return new FeishuSyncController(queueService, feishu,
                mock(ProjectRepository.class), mock(SubTaskRepository.class), mock(ScoringRepository.class),
                mock(ActivityLogRepository.class));
    }
}
