package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProjectReviewWorkflowTest {

    private ProjectRepository projects;
    private SubTaskRepository subTasks;
    private ScoringRepository scoring;
    private SubTaskDeliveryVersionRepository deliveryVersions;
    private SystemConfigRepository configs;
    private ProjectAccessService access;
    private NotificationWorkflowService notifications;
    private UserService users;
    private DefaultSubTaskCommandService service;
    private ProjectService queryService;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        subTasks = mock(SubTaskRepository.class);
        scoring = mock(ScoringRepository.class);
        deliveryVersions = mock(SubTaskDeliveryVersionRepository.class);
        configs = mock(SystemConfigRepository.class);
        access = mock(ProjectAccessService.class);
        notifications = mock(NotificationWorkflowService.class);
        users = mock(UserService.class);
        when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
        service = new DefaultSubTaskCommandService(
                projects,
                subTasks,
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
        queryService = new ProjectService(projects, subTasks, scoring, deliveryVersions, users,
                mock(ProductCategoryRepository.class), mock(IpOptionRepository.class), configs,
                mock(SyncQueueService.class), mock(FileArchiveService.class), access, notifications);
    }

    @Test
    void publishingAssignedSubTaskSchedulesNotificationForExactAssignee() {
        Project project = projectWithTask("regular", "completed");
        project.getTasks().clear();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(users.getUserByUserId("designer-2")).thenReturn(com.emie.designpm.entity.User.builder()
                .userId("designer-2").name("设计师乙").role("designer").status("active").build());
        when(users.getUserName("designer-2")).thenReturn("设计师乙");
        when(subTasks.saveAndFlush(any(SubTask.class))).thenAnswer(invocation -> {
            SubTask saved = invocation.getArgument(0);
            saved.setId(22L);
            return saved;
        });
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> {
            return invocation.getArgument(0);
        });

        service.addSubTask(1L, Map.ofEntries(
                Map.entry("name", "包装延展"), Map.entry("plannedDate", "2026-08-10"),
                Map.entry("designerId", "designer-2"), Map.entry("assigneeRole", "designer"),
                Map.entry("pointRuleCode", "A1"), Map.entry("difficultyCode", "STANDARD"),
                Map.entry("workflowStage", "design"), Map.entry("currentRole", "planner"),
                Map.entry("currentUserId", "planner-1"), Map.entry("currentUser", "企划甲"),
                Map.entry("referenceImagesJson", "[]"), Map.entry("attachmentsJson", "[]")));

        verify(notifications).notifyUserAfterCommit(
                eq("TASK_ASSIGNED"), eq("designer-2"), eq("sub_task"), eq(22L),
                eq("planner-1"), anyMap());
        verify(subTasks).saveAndFlush(any(SubTask.class));
    }

    @Test
    void createsSubTaskWithoutPointRule() {
        Project project = projectWithTask("regular", "completed");
        project.getTasks().clear();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(users.getUserByUserId("designer-2")).thenReturn(com.emie.designpm.entity.User.builder()
                .userId("designer-2").name("设计师乙").role("designer").status("active").build());
        when(users.getUserName("designer-2")).thenReturn("设计师乙");
        when(subTasks.saveAndFlush(any(SubTask.class))).thenAnswer(invocation -> {
            SubTask saved = invocation.getArgument(0);
            saved.setId(23L);
            return saved;
        });
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> service.addSubTask(1L, Map.ofEntries(
                Map.entry("name", "不计积分任务"), Map.entry("plannedDate", "2026-08-10"),
                Map.entry("designerId", "designer-2"), Map.entry("assigneeRole", "designer"),
                Map.entry("pointRuleCode", ""), Map.entry("difficultyCode", "STANDARD"),
                Map.entry("workflowStage", "design"), Map.entry("currentRole", "planner"),
                Map.entry("currentUserId", "planner-1"), Map.entry("currentUser", "企划甲"),
                Map.entry("referenceImagesJson", "[]"), Map.entry("attachmentsJson", "[]"))));
        verify(subTasks, never()).saveAndFlush(any(SubTask.class));
    }

    @Test
    void batchesDeliveryVersionsForMultipleTasks() {
        SubTask first = new SubTask(); first.setId(11L);
        SubTask second = new SubTask(); second.setId(12L);
        SubTaskDeliveryVersion newer = deliveryVersion(first, 2);
        SubTaskDeliveryVersion older = deliveryVersion(first, 1);
        SubTaskDeliveryVersion other = deliveryVersion(second, 1);
        when(deliveryVersions.findBySubTaskIdInOrderBySubTaskIdAscVersionNoDesc(List.of(11L, 12L)))
                .thenReturn(List.of(newer, older, other));

        Map<Long, List<Map<String, Object>>> result = service.getDeliveryVersionsByTaskIds(List.of(11L, 12L));

        assertEquals(List.of(2, 1), result.get(11L).stream().map(item -> item.get("versionNo")).toList());
        assertEquals(1, result.get(12L).size());
        verify(deliveryVersions).findBySubTaskIdInOrderBySubTaskIdAscVersionNoDesc(List.of(11L, 12L));
    }

    @Test
    void updatePendingSubTaskCanClearPointRuleSnapshot() {
        Project project = projectWithTask("regular", "pending");
        SubTask task = project.getTasks().get(0);
        task.setPointRuleCode("A1"); task.setDifficultyCode("COMPLEX");
        task.setBasePointSnapshot(20); task.setDifficultyMultiplierSnapshot(1.5);
        task.setQualityBonusThresholdSnapshot(90); task.setQualityBonusRatioSnapshot(.3);
        task.setQualityTopThresholdSnapshot(97); task.setQualityTopRatioSnapshot(.6);
        task.setMaxTotalMultiplierSnapshot(3d); task.setCountInPerformanceSnapshot(true);
        PointsService points = mock(PointsService.class);
        service.setPointsService(points);
        when(users.getUserByUserId("designer-1")).thenReturn(com.emie.designpm.entity.User.builder()
                .userId("designer-1").name("设计师甲").role("designer").status("active").build());
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateSubTask(1L, 11L, Map.of(
                "currentRole", "admin", "currentUserId", "admin-1", "currentUser", "管理员甲",
                "pointRuleCode", "", "difficultyCode", "STANDARD"));

        assertNull(task.getPointRuleCode());
        assertEquals("STANDARD", task.getDifficultyCode());
        assertNull(task.getBasePointSnapshot());
        assertNull(task.getDifficultyMultiplierSnapshot());
        assertNull(task.getCountInPerformanceSnapshot());
        verify(points, never()).bindRuleSnapshot(any(), anyString(), anyString());
    }

    @Test
    void updateSubTaskNormalizesNullBlankAndAliasAssigneeRoleToDesigner() {
        Project project = projectWithTask("regular", "pending");
        when(users.getUserByUserId("designer-1")).thenReturn(com.emie.designpm.entity.User.builder()
                .userId("designer-1").name("设计师甲").role("designer").status("active").build());
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> nullRole = new java.util.HashMap<>(Map.of(
                "currentRole", "admin", "currentUserId", "admin-1", "currentUser", "管理员甲"));
        nullRole.put("assigneeRole", null);
        service.updateSubTask(1L, 11L, nullRole);
        assertEquals("designer", project.getTasks().get(0).getAssigneeRole());

        service.updateSubTask(1L, 11L, Map.of(
                "currentRole", "admin", "currentUserId", "admin-1", "currentUser", "管理员甲",
                "assigneeRole", ""));
        assertEquals("designer", project.getTasks().get(0).getAssigneeRole());

        service.updateSubTask(1L, 11L, Map.of(
                "currentRole", "admin", "currentUserId", "admin-1", "currentUser", "管理员甲",
                "assigneeRole", "设计师"));
        assertEquals("designer", project.getTasks().get(0).getAssigneeRole());
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
        when(deliveryVersions.findMaxVersionNoBySubTaskId(11L)).thenReturn(1);

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
    void rejectedTaskRedeliveryResetsReviewsAndPreservesNewDeliveryVersion() {
        Project project = projectWithTask("channel_custom", "rejected");
        ScoringRecord planner = review(project.getTasks().get(0), "planner", "first");
        planner.setReviewStatus("rejected");
        planner.setComment("请补充源文件");
        ScoringRecord sales = review(project.getTasks().get(0), "sales", "second");
        sales.setReviewStatus("waiting");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "planner")).thenReturn(Optional.of(planner));
        when(scoring.findBySubTaskIdAndRole(11L, "sales")).thenReturn(Optional.of(sales));
        when(deliveryVersions.findMaxVersionNoBySubTaskId(11L)).thenReturn(1);

        Map<String, Object> body = new java.util.HashMap<>(deliveryBody());
        body.put("changeSummary", "补交源文件");
        service.taskRedeliver(1L, 11L, body);

        assertEquals("delivered", project.getTasks().get(0).getStatus());
        assertReview(planner, "planner", "first", "pending", null);
        assertReview(sales, "sales", "second", "waiting", null);
        ArgumentCaptor<com.emie.designpm.entity.SubTaskDeliveryVersion> version =
                ArgumentCaptor.forClass(com.emie.designpm.entity.SubTaskDeliveryVersion.class);
        verify(deliveryVersions).save(version.capture());
        assertEquals(2, version.getValue().getVersionNo());
        assertEquals("redelivery", version.getValue().getSubmissionType());
        assertEquals("补交源文件", version.getValue().getChangeSummary());
    }

    @Test
    void plannerApprovalCompletesFirstReviewWithAuditContext() {
        Project project = projectWithTask("channel_custom", "submitted_for_review");
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
                "comments", "二审需修改",
                "requiredCompletionDate", "2026-08-15"
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
    void deliveryLocksProjectBeforeTaskAndRejectsTaskFromAnotherProject() {
        Project project = projectWithTask("regular", "accepted");
        Project otherProject = new Project();
        otherProject.setId(2L);
        SubTask foreignTask = project.getTasks().get(0);
        foreignTask.setProject(otherProject);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(subTasks.findByIdForUpdate(11L)).thenReturn(Optional.of(foreignTask));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.taskDeliver(1L, 11L, deliveryBody()));

        assertEquals("子任务不属于当前项目", error.getMessage());
        InOrder lockOrder = inOrder(projects, subTasks);
        lockOrder.verify(projects).findByIdForUpdate(1L);
        lockOrder.verify(subTasks).findByIdForUpdate(11L);
        verify(deliveryVersions, never()).save(any());
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

        List<Map<String, Object>> result = queryService.getPendingScoringTasks("planner", "planner-1");

        assertEquals(1, result.size());
        assertEquals(12L, result.getFirst().get("taskId"));
        assertEquals(true, result.getFirst().get("isPending"));
    }

    @Test
    void scoringCenterFinalApprovalAwardsQualityCompletion() {
        Project project = projectWithTask("regular", "planner_approved");
        ScoringRecord secondReview = review(project.getTasks().get(0), "admin", "second");
        PointsService points = mock(PointsService.class);
        service.setPointsService(points);
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "admin")).thenReturn(Optional.of(secondReview));

        service.submitScoring(1L, 11L, Map.of(
                "role", "admin",
                "currentRole", "admin",
                "currentUserId", "admin-1",
                "currentUser", "管理员甲",
                "score", 91
        ));

        // 与任务详情入口一致：最终验收通过必须发放质量加分
        assertEquals("completed", project.getTasks().get(0).getStatus());
        verify(points).awardQualityCompletion(project.getTasks().get(0));
    }

    @Test
    void scoringCenterChannelSalesFinalApprovalAwardsQualityCompletion() {
        Project project = projectWithTask("channel_custom", "planner_approved");
        ScoringRecord secondReview = review(project.getTasks().get(0), "sales", "second");
        PointsService points = mock(PointsService.class);
        service.setPointsService(points);
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "sales")).thenReturn(Optional.of(secondReview));

        service.submitScoring(1L, 11L, Map.of(
                "role", "sales",
                "currentRole", "sales",
                "currentUserId", "sales-1",
                "currentUser", "销售甲",
                "score", 88
        ));

        assertEquals("completed", project.getTasks().get(0).getStatus());
        verify(points).awardQualityCompletion(project.getTasks().get(0));
    }

    @Test
    void scoringCenterFirstReviewDoesNotAwardBeforeTaskCompleted() {
        Project project = projectWithTask("regular", "submitted_for_review");
        ScoringRecord firstReview = review(project.getTasks().get(0), "planner", "first");
        PointsService points = mock(PointsService.class);
        service.setPointsService(points);
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "planner")).thenReturn(Optional.of(firstReview));
        when(scoring.findBySubTaskIdAndRole(11L, "admin")).thenReturn(Optional.empty());

        service.submitScoring(1L, 11L, Map.of(
                "role", "planner",
                "currentRole", "planner",
                "currentUserId", "planner-1",
                "currentUser", "企划甲",
                "score", 82
        ));

        // 一审通过只是中间态，未到最终验收，不得发放质量加分
        assertEquals("planner_approved", project.getTasks().get(0).getStatus());
        verify(points, never()).awardQualityCompletion(any());
    }

    @Test
    void taskDetailFinalApprovalAwardsQualityCompletion() {
        Project project = projectWithTask("regular", "planner_approved");
        ScoringRecord secondReview = review(project.getTasks().get(0), "admin", "second");
        PointsService points = mock(PointsService.class);
        service.setPointsService(points);
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoring.findBySubTaskIdAndRole(11L, "admin")).thenReturn(Optional.of(secondReview));

        service.taskApprove(1L, 11L, Map.of(
                "currentRole", "admin",
                "currentUserId", "admin-1",
                "currentUser", "管理员甲",
                "comments", "二审通过",
                "score", 91
        ));

        assertEquals("completed", project.getTasks().get(0).getStatus());
        verify(points).awardQualityCompletion(project.getTasks().get(0));
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
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(subTasks.findByIdForUpdate(11L)).thenReturn(Optional.of(task));
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

    private SubTaskDeliveryVersion deliveryVersion(SubTask task, int versionNo) {
        SubTaskDeliveryVersion version = new SubTaskDeliveryVersion();
        version.setSubTask(task); version.setVersionNo(versionNo); version.setSubmissionType("initial");
        version.setSubmittedAt(java.time.LocalDateTime.of(2026, 9, 1, 9, 0));
        return version;
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
