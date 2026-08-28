package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectLifecycleCommandServiceTest {
    @Test
    void pauseStoresPreviousStatusAndAuditLog() {
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project = new Project();
        project.setStatus("in_progress");
        when(projects.findByIdForUpdate(9L)).thenReturn(Optional.of(project));
        when(projects.save(project)).thenReturn(project);
        DefaultProjectLifecycleCommandService service = new DefaultProjectLifecycleCommandService(
                projects, mock(SubTaskRepository.class), mock(ScoringRepository.class),
                mock(SubTaskDeliveryVersionRepository.class), mock(UserService.class),
                mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class),
                mock(FileArchiveService.class), mock(ProjectAccessService.class),
                mock(NotificationWorkflowService.class));

        Project result = service.pauseProject(9L, Map.of("currentUser", "测试用户", "currentRole", "planner"));

        assertEquals("paused", result.getStatus());
        assertEquals("in_progress", result.getPrePauseStatus());
        assertEquals("项目暂停", result.getLogs().getFirst().getAction());
    }

    @Test
    void resumeRejectsNonPausedProject() {
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project = new Project(); project.setStatus("in_progress");
        when(projects.findByIdForUpdate(9L)).thenReturn(Optional.of(project));
        DefaultProjectLifecycleCommandService service = new DefaultProjectLifecycleCommandService(
                projects, mock(SubTaskRepository.class), mock(ScoringRepository.class),
                mock(SubTaskDeliveryVersionRepository.class), mock(UserService.class),
                mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class),
                mock(FileArchiveService.class), mock(ProjectAccessService.class),
                mock(NotificationWorkflowService.class));
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.resumeProject(9L, Map.of()));
        assertEquals("只有暂停中的项目可以继续", error.getMessage());
    }
}
