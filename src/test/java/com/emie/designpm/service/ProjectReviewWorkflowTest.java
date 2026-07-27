package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProjectReviewWorkflowTest {

    private ProjectRepository projects;
    private ScoringRepository scoring;
    private SubTaskDeliveryVersionRepository deliveryVersions;
    private SystemConfigRepository configs;
    private ProjectAccessService access;
    private NotificationWorkflowService notifications;
    private UserService users;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        scoring = mock(ScoringRepository.class);
        deliveryVersions = mock(SubTaskDeliveryVersionRepository.class);
        configs = mock(SystemConfigRepository.class);
        access = mock(ProjectAccessService.class);
        notifications = mock(NotificationWorkflowService.class);
        users = mock(UserService.class);
        when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
        service = new ProjectService(
                projects,
                mock(SubTaskRepository.class),
                scoring,
                deliveryVersions,
                users,
                mock(ProductCategoryRepository.class),
                mock(IpOptionRepository.class),
                configs,
                mock(SyncQueueService.class),
                mock(FileArchiveService.class),
                access,
                notifications
        );
    }

    @Test
    void publishingAssignedSubTaskSchedulesNotificationForExactAssignee() {
        Project project = projectWithTask("regular", "completed");
        project.getTasks().clear();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(users.getUserByUserId("designer-2")).thenReturn(com.emie.designpm.entity.User.builder()
                .userId("designer-2").name("设计师乙").role("designer").status("active").build());
        when(users.getUserName("designer-2")).thenReturn("设计师乙");
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> {
            Project saved = invocation.getArgument(0);
            saved.getTasks().getFirst().setId(22L);
            return saved;
        });

        service.addSubTask(1L, Map.of(
                "name", "包装延展", "plannedDate", "2026-08-10",
                "designerId", "designer-2", "assigneeRole", "designer",
                "workflowStage", "design", "currentRole", "planner",
                "currentUserId", "planner-1", "currentUser", "企划甲",
                "referenceImagesJson", "[]", "attachmentsJson", "[]"));

        verify(notifications).notifyUserAfterCommit(
                eq("TASK_ASSIGNED"), eq("designer-2"), eq("sub_task"), eq(22L),
                eq("planner-1"), anyMap());
    }

    @Test
    void channelDeliveryCreatesPlannerAndSalesReviewRows() {
        Project project = projectWithTask("channel_custom", "accepted");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(any(), anyString())).thenReturn(Optional.empty());

        service.taskDeliver(1L, 11L, deliveryBody());

        List<ScoringRecord> records = savedScoringRecords();
        assertReview(records.get(0), "planner", "first", "pending", null);
        assertReview(records.get(1), "sales", "second", "waiting", null);
        assertEquals("delivered", project.getTasks().get(0).getStatus());
    }

    @Test
    void regularDeliveryCreatesPlannerAndAdminReviewRows() {
        Project project = projectWithTask("regular", "accepted");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(any(), anyString())).thenReturn(Optional.empty());

        service.taskDeliver(1L, 11L, deliveryBody());

        List<ScoringRecord> records = savedScoringRecords();
        assertReview(records.get(0), "planner", "first", "pending", null);
        assertReview(records.get(1), "admin", "second", "waiting", null);
    }

    @Test
    void activeDeliveryCorrectionCreatesVersionAndInvalidatesPreviousApproval() {
        Project project = projectWithTask("channel_custom", "planner_approved");
        ScoringRecord planner = review(project.getTasks().get(0), "planner", "first");
        planner.setReviewStatus("approved");
        planner.setScore(90);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "planner")).thenReturn(Optional.of(planner));
        when(scoring.findBySubTaskIdAndRole(11L, "sales")).thenReturn(Optional.empty());
        when(deliveryVersions.countBySubTaskId(11L)).thenReturn(1L);

        Map<String, Object> body = new java.util.HashMap<>(deliveryBody());
        body.put("changeSummary", "补充源文件并移除错误附件");
        body.put("deliverables", "V2 完整交付");
        service.taskCorrectDelivery(1L, 11L, body);

        assertEquals("delivered", project.getTasks().get(0).getStatus());
        assertReview(planner, "planner", "first", "pending", null);
        ArgumentCaptor<com.emie.designpm.entity.SubTaskDeliveryVersion> version =
                ArgumentCaptor.forClass(com.emie.designpm.entity.SubTaskDeliveryVersion.class);
        verify(deliveryVersions).save(version.capture());
        assertEquals(2, version.getValue().getVersionNo());
        assertEquals("correction", version.getValue().getSubmissionType());
        assertEquals("补充源文件并移除错误附件", version.getValue().getChangeSummary());
    }

    @Test
    void plannerApprovalCompletesFirstReviewWithAuditContext() {
        Project project = projectWithTask("channel_custom", "delivered");
        ScoringRecord firstReview = review(project.getTasks().get(0), "planner", "first");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "planner")).thenReturn(Optional.of(firstReview));

        service.taskApprove(1L, 11L, Map.of(
                "currentRole", "planner",
                "currentUserId", "planner-1",
                "currentUser", "企划甲",
                "comments", "一审通过",
                "score", 82
        ));

        assertReview(firstReview, "planner", "first", "approved", 82);
        assertEquals("planner-1", firstReview.getReviewerId());
        assertEquals("企划甲", firstReview.getReviewerName());
        assertEquals("一审通过", firstReview.getComment());
        assertNotNull(firstReview.getReviewedAt());
        assertEquals("planner_approved", project.getTasks().get(0).getStatus());
        ArgumentCaptor<ScoringRecord> captor = ArgumentCaptor.forClass(ScoringRecord.class);
        verify(scoring, times(2)).save(captor.capture());
        ScoringRecord secondReview = captor.getAllValues().stream()
                .filter(record -> "sales".equals(record.getRole()))
                .findFirst().orElseThrow();
        assertReview(secondReview, "sales", "second", "pending", null);
    }

    @Test
    void salesRejectionMarksSecondReviewRejected() {
        Project project = projectWithTask("channel_custom", "planner_approved");
        ScoringRecord secondReview = review(project.getTasks().get(0), "sales", "second");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "sales")).thenReturn(Optional.of(secondReview));

        service.taskReject(1L, 11L, Map.of(
                "currentRole", "sales",
                "currentUserId", "sales-1",
                "currentUser", "销售甲",
                "comments", "二审需修改"
        ));

        assertReview(secondReview, "sales", "second", "rejected", null);
        assertEquals("sales-1", secondReview.getReviewerId());
        assertEquals("销售甲", secondReview.getReviewerName());
        assertEquals("二审需修改", secondReview.getComment());
        assertNotNull(secondReview.getReviewedAt());
        assertEquals("rejected", project.getTasks().get(0).getStatus());
        assertEquals("sub_task", project.getLogs().get(0).getEntityType());
        assertEquals(11L, project.getLogs().get(0).getEntityId());
        assertTrue(project.getLogs().get(0).getBeforeData().contains("\"deliverables\""));
        assertTrue(project.getLogs().get(0).getAfterData().contains("二审需修改"));
        assertTrue(project.getLogs().get(0).getAfterData().contains("rejectionReferenceImagesJson"));
        assertTrue(project.getLogs().get(0).getAfterData().contains("rejectionAttachmentsJson"));
    }

    @Test
    void regularAdminApprovalCompletesSecondReviewAndTask() {
        Project project = projectWithTask("regular", "planner_approved");
        project.getTasks().get(0).setWorkflowStage("bulk");
        ScoringRecord secondReview = review(project.getTasks().get(0), "admin", "second");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "admin")).thenReturn(Optional.of(secondReview));

        service.taskApprove(1L, 11L, Map.of(
                "currentRole", "admin",
                "currentUserId", "admin-1",
                "currentUser", "管理员甲",
                "comments", "二审通过",
                "score", 91
        ));

        assertReview(secondReview, "admin", "second", "approved", 91);
        assertEquals("管理员甲", secondReview.getReviewerName());
        assertEquals("completed", project.getTasks().get(0).getStatus());
        assertEquals("completed", project.getStatus());
    }

    @Test
    void projectOnlyCompletesAfterBulkStageTasksAreComplete() {
        Project project = projectWithTask("regular", "completed");
        project.getTasks().get(0).setWorkflowStage("design");
        assertEquals("in_progress", ProjectService.computeProjectStatusStatic(project));

        project.getTasks().get(0).setWorkflowStage("bulk");
        assertEquals("completed", ProjectService.computeProjectStatusStatic(project));
    }

    @Test
    void scoringCenterExcludesRejectedTasks() {
        Project project = projectWithTask("regular", "rejected");
        SubTask rejected = project.getTasks().get(0);
        ScoringRecord rejectedReview = review(rejected, "planner", "first");
        rejectedReview.setReviewStatus("rejected");

        SubTask pending = new SubTask();
        pending.setId(12L);
        pending.setName("保留的待评分任务");
        pending.setStatus("delivered");
        pending.setPlannedDate("2026-07-22");
        pending.setProject(project);
        project.getTasks().add(pending);
        ScoringRecord pendingReview = review(pending, "planner", "first");

        when(access.findVisibleProjectsLight("planner", "planner-1")).thenReturn(List.of(project));
        when(scoring.findBySubTaskIds(List.of(11L, 12L))).thenReturn(List.of(rejectedReview, pendingReview));

        List<Map<String, Object>> result = service.getPendingScoringTasks("planner", "planner-1");

        assertEquals(1, result.size());
        assertEquals(12L, result.getFirst().get("taskId"));
        assertEquals(true, result.getFirst().get("isPending"));
    }

    private Project projectWithTask(String type, String taskStatus) {
        Project project = new Project();
        project.setId(1L);
        project.setType(type);
        project.setStatus("in_progress");
        project.setPlannerId("planner-1");
        project.setPlannerName("企划甲");
        project.setSalesId("sales-1");
        project.setSalesName("销售甲");

        SubTask task = new SubTask();
        task.setId(11L);
        task.setName("包装设计");
        task.setStatus(taskStatus);
        task.setDesignerId("designer-1");
        task.setDesignerName("设计师甲");
        task.setPlannedDate("2026-07-20");
        task.setProject(project);
        project.getTasks().add(task);
        return project;
    }

    private Map<String, Object> deliveryBody() {
        return Map.of(
                "currentRole", "designer",
                "currentUserId", "designer-1",
                "currentUser", "设计师甲",
                "actualDate", "2026-07-15",
                "deliverables", "已交付",
                "referenceImagesJson", "[]",
                "attachmentsJson", "[]",
                "selfScore", 88
        );
    }

    private ScoringRecord review(SubTask task, String role, String stage) {
        ScoringRecord record = new ScoringRecord();
        record.setId("first".equals(stage) ? 101L : 102L);
        record.setRole(role);
        record.setScoreType(role);
        record.setReviewStage(stage);
        record.setReviewStatus("pending");
        record.setWeight(0.25);
        record.setSubTask(task);
        return record;
    }

    private List<ScoringRecord> savedScoringRecords() {
        ArgumentCaptor<ScoringRecord> captor = ArgumentCaptor.forClass(ScoringRecord.class);
        verify(scoring, times(2)).save(captor.capture());
        return captor.getAllValues();
    }

    private void assertReview(ScoringRecord record, String role, String stage,
                              String status, Integer score) {
        assertEquals(role, record.getRole());
        assertEquals(stage, record.getReviewStage());
        assertEquals(status, record.getReviewStatus());
        assertEquals(score, record.getScore());
        assertNotNull(record.getWeight());
        assertNotNull(record.getSubTask());
    }
}
