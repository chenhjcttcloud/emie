package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.service.UserService;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.entity.SubTaskRejectionCycle;
import com.emie.designpm.entity.User;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.points.service.PointsService;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskDeliveryVersionRepository;
import com.emie.designpm.project.repository.SubTaskRejectionCycleRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.reference.repository.IpOptionRepository;
import com.emie.designpm.reference.repository.ProductCategoryRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.sync.service.SyncQueueService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ProjectCollaboratorTaskCommandTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final SubTaskRepository tasks = mock(SubTaskRepository.class);
    private final ProjectCollaboratorRepository collaborators = mock(ProjectCollaboratorRepository.class);
    private final UserService users = mock(UserService.class);
    private final NotificationWorkflowService notifications = mock(NotificationWorkflowService.class);
    private final SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
    private final SubTaskRejectionCycleRepository rejections = mock(SubTaskRejectionCycleRepository.class);
    private DefaultSubTaskCommandService service;
    private Project project;
    private SubTask task;

    @BeforeEach
    void setUp() {
        service = new DefaultSubTaskCommandService(
                projects,
                tasks,
                mock(ScoringRepository.class),
                versions,
                users,
                mock(ProductCategoryRepository.class),
                mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class),
                mock(SyncQueueService.class),
                mock(FileArchiveService.class),
                mock(ProjectAccessService.class),
                notifications);
        service.setTaskManagementPolicy(new ProjectTaskManagementPolicy(collaborators));
        service.setRejectionCycleRepository(rejections);
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

    @ParameterizedTest
    @CsvSource({"regular,delivered", "channel_custom,submitted_for_review", "regular,planner_approved"})
    void collaboratorConfirmsOwnPublishedTaskAndUsesExistingPointSettlement(String projectType, String status) {
        project.setType(projectType);
        task.setStatus(status);
        task.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
        SubTaskDeliveryVersion version = new SubTaskDeliveryVersion();
        version.setId(21L);
        version.setSubTask(task);
        version.setVersionNo(1);
        version.setExpectedPoints(10d);
        when(versions.findFirstBySubTaskIdOrderByVersionNoDesc(2L)).thenReturn(Optional.of(version));
        PointsService points = mock(PointsService.class);
        service.setPointsService(points);
        Map<String, Object> body = actor();
        body.put("expectedDeliveryVersionId", 21L);
        body.put("comments", "确认成果");

        service.taskApprove(1L, 2L, body);

        assertEquals("completed", task.getStatus());
        assertEquals("确认成果", task.getReviewComments());
        assertNotNull(version.getConfirmedAt());
        assertEquals("collaborator", version.getConfirmedBy());
        verify(points).awardBaseSubmission(task, version, "collaborator");
        verify(versions).save(version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"collaborator", "owner"})
    void collaboratorAndOwnerCanRejectAndCancelRejectionOfCollaboratorPublishedTask(String reviewer) {
        task.setStatus("submitted_for_review");
        task.setPlannedDate("2026-12-01");
        when(rejections.save(any())).thenAnswer(invocation -> {
            SubTaskRejectionCycle cycle = invocation.getArgument(0);
            cycle.setId(31L);
            return cycle;
        });
        Map<String, Object> body = reviewBody(reviewer);

        service.taskReject(1L, 2L, body);

        ArgumentCaptor<SubTaskRejectionCycle> saved = ArgumentCaptor.forClass(SubTaskRejectionCycle.class);
        verify(rejections).save(saved.capture());
        SubTaskRejectionCycle cycle = saved.getValue();
        assertEquals("rejected", task.getStatus());
        assertEquals(reviewer, cycle.getRejectedById());
        assertEquals("2026-12-03", task.getPlannedDate());
        when(rejections.findById(31L)).thenReturn(Optional.of(cycle));
        when(rejections.findFirstBySubTaskIdAndStatusOrderBySequenceNoDesc(2L, "ACTIVE"))
                .thenReturn(Optional.of(cycle));

        service.taskCancelReject(1L, 2L, 31L, body);

        assertEquals("submitted_for_review", task.getStatus());
        assertEquals("2026-12-01", task.getPlannedDate());
        assertEquals("CANCELLED", cycle.getStatus());
        assertEquals(reviewer, cycle.getCancelledById());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "another-publisher", ""})
    void collaboratorCannotReviewOtherPublishersTaskEvenWithForgedPublisherFields(String publisher) {
        task.setStatus("submitted_for_review");
        task.setPublisherId(publisher);
        Map<String, Object> body = reviewBody("collaborator");
        body.put("publisherId", "collaborator");
        body.put("plannerId", "collaborator");

        assertThrows(RuntimeException.class, () -> service.taskApprove(1L, 2L, body));
        assertThrows(RuntimeException.class, () -> service.taskReject(1L, 2L, body));

        assertEquals("submitted_for_review", task.getStatus());
        verify(projects, never()).save(any());
        verify(rejections, never()).save(any());
    }

    @Test
    void removedCollaboratorCannotConfirmRejectOrCancelEvenForHistoricalPublishedTask() {
        when(collaborators.existsByProjectIdAndUserId(1L, "collaborator")).thenReturn(false);
        task.setStatus("submitted_for_review");
        Map<String, Object> body = reviewBody("collaborator");
        assertThrows(RuntimeException.class, () -> service.taskApprove(1L, 2L, body));
        assertThrows(RuntimeException.class, () -> service.taskReject(1L, 2L, body));
        task.setStatus("rejected");
        SubTaskRejectionCycle cycle = new SubTaskRejectionCycle();
        cycle.setId(31L);
        cycle.setSubTask(task);
        cycle.setRejectionRole("planner");
        cycle.setPriorTaskStatus("submitted_for_review");
        when(rejections.findById(31L)).thenReturn(Optional.of(cycle));
        when(rejections.findFirstBySubTaskIdAndStatusOrderBySequenceNoDesc(2L, "ACTIVE"))
                .thenReturn(Optional.of(cycle));

        assertThrows(RuntimeException.class, () -> service.taskCancelReject(1L, 2L, 31L, body));

        assertEquals("rejected", task.getStatus());
        assertEquals("ACTIVE", cycle.getStatus());
        verify(projects, never()).save(any());
        verify(rejections, never()).save(any());
    }

    @Test
    void ownerStillConfirmsCollaboratorTaskAndCollaboratorCannotInitiateCompletedTaskChangeBonus() {
        task.setStatus("delivered");
        task.setAssigneeRole("supplychain");
        service.taskApprove(1L, 2L, reviewBody("owner"));
        assertEquals("completed", task.getStatus());

        assertThrows(IllegalArgumentException.class, () -> service.requestChangeBonus(1L, 2L, actor()));
    }

    private Map<String, Object> reviewBody(String userId) {
        Map<String, Object> body = actor();
        body.put("currentUserId", userId);
        body.put("comments", "请补充成果");
        body.put("requiredCompletionDate", "2026-12-03");
        return body;
    }

    private Map<String, Object> actor() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentRole", "planner");
        body.put("currentUserId", "collaborator");
        body.put("currentUser", "协作企划");
        return body;
    }
}
