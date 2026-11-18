package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.service.UserService;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskDeliveryVersionRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.reference.repository.IpOptionRepository;
import com.emie.designpm.reference.repository.ProductCategoryRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.sync.service.SyncQueueService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectCollaboratorTaskCommandTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final SubTaskRepository tasks = mock(SubTaskRepository.class);
    private final ProjectCollaboratorRepository collaborators = mock(ProjectCollaboratorRepository.class);
    private final UserService users = mock(UserService.class);
    private final NotificationWorkflowService notifications = mock(NotificationWorkflowService.class);
    private DefaultSubTaskCommandService service;
    private Project project;
    private SubTask task;

    @BeforeEach
    void setUp() {
        service = new DefaultSubTaskCommandService(
                projects,
                tasks,
                mock(ScoringRepository.class),
                mock(SubTaskDeliveryVersionRepository.class),
                users,
                mock(ProductCategoryRepository.class),
                mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class),
                mock(SyncQueueService.class),
                mock(FileArchiveService.class),
                mock(ProjectAccessService.class),
                notifications);
        service.setTaskManagementPolicy(new ProjectTaskManagementPolicy(collaborators));
        project = new Project();
        project.setId(1L);
        project.setPlannerId("owner");
        project.setProductName("送审项目");
        project.setType("regular");
        project.setStatus("in_progress");
        task = new SubTask();
        task.setId(2L);
        task.setName("送审资料");
        task.setProject(project);
        task.setPublisherId("collaborator");
        task.setDesignerId("designer");
        task.setAssigneeRole("designer");
        task.setStatus("pending");
        task.setAllocationStatus("direct_assigned");
        project.getTasks().add(task);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(tasks.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        when(projects.saveAndFlush(project)).thenReturn(project);
        when(projects.save(project)).thenReturn(project);
        when(collaborators.existsByProjectIdAndUserId(1L, "collaborator")).thenReturn(true);
        when(users.getUserByUserId("designer"))
                .thenReturn(User.builder()
                        .userId("designer")
                        .name("设计师")
                        .role("designer")
                        .build());
        when(users.getUserName("designer")).thenReturn("设计师");
    }

    @Test
    void collaboratorPublishesTaskWithTheirIdentityAndNotifiesAssignee() {
        when(tasks.saveAndFlush(any(SubTask.class))).thenAnswer(invocation -> {
            SubTask saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        Map<String, Object> body = actor();
        body.putAll(Map.of(
                "name",
                "送审包装",
                "plannedDate",
                "2026-12-01",
                "designerId",
                "designer",
                "assigneeRole",
                "designer",
                "pointRuleCode",
                "A1",
                "workflowStage",
                "design_review"));

        service.addSubTask(1L, body);

        ArgumentCaptor<SubTask> saved = ArgumentCaptor.forClass(SubTask.class);
        verify(tasks).saveAndFlush(saved.capture());
        assertEquals("collaborator", saved.getValue().getPublisherId());
        assertEquals("owner", project.getPlannerId());
        verify(notifications)
                .notifyUserAfterCommit(
                        eq("TASK_ASSIGNED"), eq("designer"), eq("sub_task"), eq(3L), eq("collaborator"), anyMap());
        var order = inOrder(projects, collaborators, tasks);
        order.verify(projects).findByIdForUpdate(1L);
        order.verify(collaborators).existsByProjectIdAndUserId(1L, "collaborator");
        order.verify(tasks).saveAndFlush(any(SubTask.class));
    }

    @Test
    void collaboratorEditsOwnTaskButCannotEditOtherPublishersTask() {
        Map<String, Object> body = actor();
        body.put("name", "更新后的送审资料");
        service.updateSubTask(1L, 2L, body);
        assertEquals("更新后的送审资料", task.getName());

        task.setPublisherId("owner");
        body.put("name", "不应保存");
        assertThrows(SecurityException.class, () -> service.updateSubTask(1L, 2L, body));
        assertEquals("更新后的送审资料", task.getName());
    }

    @Test
    void collaboratorCanWithdrawAndCancelOnlyOwnPublishedTask() {
        task.setAllocationStatus("market_open");
        task.setDesignerId(null);
        service.withdrawMarketTask(1L, 2L, actor());
        assertEquals("withdrawn", task.getAllocationStatus());

        task.setStatus("accepted");
        task.setDesignerId("designer");
        service.cancelAcceptedTask(1L, 2L, actor());
        assertEquals("pending", task.getStatus());
        assertNull(task.getDesignerId());
        task.setPublisherId("owner");
        task.setStatus("accepted");
        assertThrows(SecurityException.class, () -> service.cancelAcceptedTask(1L, 2L, actor()));
    }

    @Test
    void removedCollaboratorCannotManageHistoricalPublishedTasks() {
        when(collaborators.existsByProjectIdAndUserId(1L, "collaborator")).thenReturn(false);
        assertThrows(SecurityException.class, () -> service.addSubTask(1L, actor()));
        assertThrows(SecurityException.class, () -> service.updateSubTask(1L, 2L, actor()));
        assertThrows(SecurityException.class, () -> service.deleteSubTask(1L, 2L, actor()));
        verify(tasks, never()).delete(any());
    }

    @Test
    void collaboratorCannotApproveTheirOwnPublishedTask() {
        task.setStatus("delivered");
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.taskApprove(1L, 2L, actor()));
        assertTrue(error.getMessage().contains("项目负责人"));
        assertEquals("delivered", task.getStatus());
    }

    private Map<String, Object> actor() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentRole", "planner");
        body.put("currentUserId", "collaborator");
        body.put("currentUser", "协作企划");
        return body;
    }
}
