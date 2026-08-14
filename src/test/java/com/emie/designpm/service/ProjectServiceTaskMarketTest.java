package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ProjectServiceTaskMarketTest {

    @Test
    void openMarketTaskIsClaimedByCurrentDesigner() {
        Fixture fixture = fixture("market_open");

        fixture.service.taskAccept(1L, 2L, Map.of(
                "currentRole", "designer", "currentUser", "张设计",
                "currentUserId", "designer-1", "designerUserId", "designer-1"));

        assertEquals("designer-1", fixture.task.getDesignerId());
        assertEquals("张设计", fixture.task.getDesignerName());
        assertEquals("claimed", fixture.task.getAllocationStatus());
        assertEquals("accepted", fixture.task.getStatus());
    }

    @Test
    void withdrawnTaskCannotBeClaimed() {
        Fixture fixture = fixture("withdrawn");

        RuntimeException error = assertThrows(RuntimeException.class, () -> fixture.service.taskAccept(1L, 2L, Map.of(
                "currentRole", "designer", "currentUser", "张设计",
                "currentUserId", "designer-1", "designerUserId", "designer-1")));

        assertEquals("该子任务未开放接单", error.getMessage());
    }

    @Test
    void configuredMainTaskLimitBlocksClaimBeforeMutation() {
        Fixture fixture = fixture("market_open");
        fixture.task.setPointRuleCode("A1");
        when(fixture.tasks.countActiveMainTasksByCategory("designer-1", "A")).thenReturn(2L);
        when(fixture.tasks.countActiveMainTasksByCategory("designer-1", "B")).thenReturn(1L);
        SystemConfig limit = new SystemConfig();
        limit.setConfigValue("3");
        when(fixture.configs.findByConfigKey("points.claim.max_main_tasks")).thenReturn(Optional.of(limit));

        RuntimeException error = assertThrows(RuntimeException.class, () -> fixture.service.taskAccept(1L, 2L, Map.of(
                "currentRole", "designer", "currentUser", "张设计",
                "currentUserId", "designer-1", "designerUserId", "designer-1")));

        assertEquals("当前A/B类主任务已达上限（3个），请完成现有任务后再接单", error.getMessage());
        assertEquals(null, fixture.task.getDesignerId());
    }

    @Test
    void legacySkillTagsDoNotBlockDesignerClaim() {
        Fixture fixture = fixture("market_open");
        fixture.task.setRequiredSkillTagsJson("[\"包装\",\"3D\"]");
        SystemConfig skills = new SystemConfig();
        skills.setConfigValue("[\"包装\"]");
        when(fixture.configs.findByConfigKey("points.user.skills.designer-1")).thenReturn(Optional.of(skills));

        fixture.service.taskAccept(1L, 2L, Map.of(
                "currentRole", "designer", "currentUser", "张设计",
                "currentUserId", "designer-1", "designerUserId", "designer-1"));
        assertEquals("designer-1", fixture.task.getDesignerId());
    }

    private Fixture fixture(String allocationStatus) {
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        UserService users = mock(UserService.class);
        FileArchiveService files = mock(FileArchiveService.class);
        NotificationWorkflowService notifications = mock(NotificationWorkflowService.class);
        Project project = new Project();
        project.setId(1L);
        project.setStatus("in_progress");
        SubTask task = new SubTask();
        task.setId(2L); task.setName("包装设计"); task.setStatus("pending");
        task.setAssigneeRole("designer"); task.setAllocationStatus(allocationStatus); task.setProject(project);
        project.getTasks().add(task);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(tasks.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        when(projects.saveAndFlush(project)).thenReturn(project);
        when(users.getUserName("designer-1")).thenReturn("张设计");
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        ProjectService service = new ProjectService(projects, tasks, mock(ScoringRepository.class),
                mock(SubTaskDeliveryVersionRepository.class), users, mock(ProductCategoryRepository.class),
                mock(IpOptionRepository.class), configs, mock(SyncQueueService.class),
                files, mock(ProjectAccessService.class), notifications);
        return new Fixture(service, task, tasks, configs);
    }

    private record Fixture(ProjectService service, SubTask task, SubTaskRepository tasks,
                           SystemConfigRepository configs) {}
}
