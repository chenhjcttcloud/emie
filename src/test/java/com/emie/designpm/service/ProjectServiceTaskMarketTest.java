package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.PointAppeal;
import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.TaskWithdrawal;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    void deleteProjectCleansOrphanRelatedDataBeforeRemovingProject() {
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        TaskWithdrawalRepository withdrawals = mock(TaskWithdrawalRepository.class);
        PointLedgerRepository ledgers = mock(PointLedgerRepository.class);
        PointAppealRepository appeals = mock(PointAppealRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        FileRecordRepository files = mock(FileRecordRepository.class);

        Project project = new Project();
        project.setId(1L);
        SubTask t1 = new SubTask(); t1.setId(10L); t1.setProject(project);
        SubTask t2 = new SubTask(); t2.setId(11L); t2.setProject(project);
        when(tasks.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(t1, t2));
        TaskWithdrawal w = new TaskWithdrawal(); w.setId(100L);
        when(withdrawals.findBySubTaskIdIn(List.of(10L, 11L))).thenReturn(List.of(w));
        PointLedger l1 = new PointLedger(); l1.setId(200L);
        when(ledgers.findBySubTaskIdIn(List.of(10L, 11L))).thenReturn(List.of(l1));
        PointAppeal a1 = new PointAppeal(); a1.setId(300L);
        when(appeals.findByPointLedgerIdIn(List.of(200L))).thenReturn(List.of(a1));

        ProjectService service = new ProjectService(projects, tasks, scoring, versions,
                mock(UserService.class), mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));
        service.setTaskWithdrawalRepository(withdrawals);
        service.setPointLedgerRepository(ledgers);
        service.setPointAppealRepository(appeals);
        service.setPointAdjustmentLedgerRepository(adjustments);
        service.setNotificationRepository(notifications);
        service.setFileRecordRepository(files);

        service.deleteProject(1L);

        InOrder order = inOrder(adjustments, withdrawals, appeals, ledgers, versions, scoring, notifications, files, projects);
        order.verify(adjustments).deleteProjectRelated(List.of(300L), List.of(100L));
        order.verify(withdrawals).deleteBySubTaskIds(List.of(10L, 11L));
        order.verify(appeals).deleteByPointLedgerIds(List.of(200L));
        order.verify(ledgers).deleteBySubTaskIds(List.of(10L, 11L));
        order.verify(versions).deleteBySubTaskIds(List.of(10L, 11L));
        order.verify(scoring).deleteByProjectId(1L);
        order.verify(notifications).deleteByAggregateTypeAndAggregateIdIn("project", List.of(1L));
        order.verify(notifications).deleteByAggregateTypeAndAggregateIdIn("sub_task", List.of(10L, 11L));
        order.verify(files).deleteByTargetTypeAndTargetIdIn("project", List.of(1L));
        order.verify(files).deleteByTargetTypeAndTargetIdIn("sub_task", List.of(10L, 11L));
        order.verify(projects).deleteById(1L);
    }

    @Test
    void createProjectBumpsMonthSequenceWhenGeneratedCodeIsAlreadyTaken() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserService users = mock(UserService.class);
        when(users.getUserName(anyString())).thenReturn("企划甲");
        when(users.getUserByUserId(anyString())).thenReturn(plannerUser());
        ProjectService service = new ProjectService(projects, mock(SubTaskRepository.class),
                mock(ScoringRepository.class), mock(SubTaskDeliveryVersionRepository.class),
                users, mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));
        when(projects.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(0L);

        LocalDateTime now = LocalDateTime.now();
        String taken = String.format("EMIE%04d%02d%04d", now.getYear(), now.getMonthValue(), 1);
        String free = String.format("EMIE%04d%02d%04d", now.getYear(), now.getMonthValue(), 2);
        when(projects.existsByProjectCode(taken)).thenReturn(true);
        when(projects.existsByProjectCode(free)).thenReturn(false);
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> {
            Project saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        Project created = service.createProject(regularProjectBody());

        assertEquals(free, created.getProjectCode());
        verify(projects).existsByProjectCode(taken);
        verify(projects).existsByProjectCode(free);
    }

    @Test
    void createProjectConvertsFinalUniqueConflictToChineseBusinessError() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserService users = mock(UserService.class);
        when(users.getUserName(anyString())).thenReturn("企划甲");
        when(users.getUserByUserId(anyString())).thenReturn(plannerUser());
        ProjectService service = new ProjectService(projects, mock(SubTaskRepository.class),
                mock(ScoringRepository.class), mock(SubTaskDeliveryVersionRepository.class),
                users, mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));
        when(projects.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(0L);
        // 预检查与保存之间的跨实例竞态：查重通过但保存时唯一索引冲突
        when(projects.existsByProjectCode(anyString())).thenReturn(false);
        when(projects.saveAndFlush(any(Project.class)))
                .thenThrow(new DataIntegrityViolationException("uk_projects_project_code"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.createProject(regularProjectBody()));

        assertEquals("项目编号生成冲突，请稍后重试", error.getMessage());
    }

    @Test
    void deleteSubTaskCleansWithdrawalAndAdjustmentDataBeforeRemovingSubTask() {
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        TaskWithdrawalRepository withdrawals = mock(TaskWithdrawalRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        Project project = new Project(); project.setId(1L); project.setStatus("pending_planner");
        SubTask task = new SubTask(); task.setId(2L); task.setName("包装设计"); task.setStatus("pending"); task.setProject(project);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(tasks.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        TaskWithdrawal withdrawal = new TaskWithdrawal(); withdrawal.setId(7L);
        when(withdrawals.findBySubTaskIdIn(List.of(2L))).thenReturn(List.of(withdrawal));
        when(scoring.findBySubTaskId(2L)).thenReturn(List.of());
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectService service = new ProjectService(projects, tasks, scoring, versions,
                mock(UserService.class), mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));
        service.setTaskWithdrawalRepository(withdrawals);
        service.setPointAdjustmentLedgerRepository(adjustments);

        service.deleteSubTask(1L, 2L);

        // 清理顺序必须满足 FK 依赖：退单调账 → 退单(FK→sub_tasks) → 交付版本(FK→sub_tasks) → 评分 → 子任务
        InOrder order = inOrder(adjustments, withdrawals, versions, scoring, tasks, projects);
        order.verify(adjustments).deleteProjectRelated(List.of(), List.of(7L));
        order.verify(withdrawals).deleteBySubTaskIds(List.of(2L));
        order.verify(versions).deleteBySubTaskIds(List.of(2L));
        order.verify(scoring).deleteAll(List.of());
        order.verify(tasks).delete(task);
        order.verify(projects).save(project);
    }

    @Test
    void deleteSubTaskRejectsWhenTaskIsNoLongerPendingAfterLockReload() {
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        Project project = new Project(); project.setId(1L); project.setStatus("in_progress");
        // 抢单并发：快照校验通过后、锁内重载时任务已被设计师认领（pending -> accepted）
        SubTask task = new SubTask(); task.setId(2L); task.setName("包装设计");
        task.setStatus("accepted"); task.setProject(project);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(tasks.findByIdForUpdate(2L)).thenReturn(Optional.of(task));

        ProjectService service = new ProjectService(projects, tasks, scoring, versions,
                mock(UserService.class), mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.deleteSubTask(1L, 2L));

        assertEquals("项目已进入工作流程，无法删除子任务", error.getMessage());
        // 拒绝删除：任何 FK 清理与删除动作都不应发生
        verify(scoring, never()).findBySubTaskId(any());
        verify(versions, never()).deleteBySubTaskIds(any());
        verify(tasks, never()).delete(any());
        verify(projects, never()).save(any());
    }

    @Test
    void deleteSubTaskStillAllowsPendingTaskInInProgressProject() {
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        Project project = new Project(); project.setId(1L); project.setStatus("in_progress");
        // 与锁内重校验对照：项目已进入工作流程但任务仍为 pending，删除依然允许
        SubTask task = new SubTask(); task.setId(2L); task.setName("包装设计");
        task.setStatus("pending"); task.setProject(project);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(tasks.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        when(scoring.findBySubTaskId(2L)).thenReturn(List.of());
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectService service = new ProjectService(projects, tasks, scoring, versions,
                mock(UserService.class), mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));

        service.deleteSubTask(1L, 2L);

        verify(versions).deleteBySubTaskIds(List.of(2L));
        verify(tasks).delete(task);
        verify(projects).save(project);
    }

    @Test
    void deleteSubTaskSkipsWithdrawalCleanupWhenNoWithdrawalsExist() {
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        TaskWithdrawalRepository withdrawals = mock(TaskWithdrawalRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        Project project = new Project(); project.setId(1L); project.setStatus("pending_planner");
        SubTask task = new SubTask(); task.setId(2L); task.setName("包装设计");
        task.setStatus("pending"); task.setProject(project);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(tasks.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        when(withdrawals.findBySubTaskIdIn(List.of(2L))).thenReturn(List.of());
        when(scoring.findBySubTaskId(2L)).thenReturn(List.of());
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectService service = new ProjectService(projects, tasks, scoring, versions,
                mock(UserService.class), mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));
        service.setTaskWithdrawalRepository(withdrawals);
        service.setPointAdjustmentLedgerRepository(adjustments);

        service.deleteSubTask(1L, 2L);

        // 无退单记录：调账与退单清理被空集合守卫跳过，其余 FK 清理顺序保持不变
        verify(adjustments, never()).deleteProjectRelated(any(), any());
        verify(withdrawals, never()).deleteBySubTaskIds(any());
        InOrder order = inOrder(versions, scoring, tasks, projects);
        order.verify(versions).deleteBySubTaskIds(List.of(2L));
        order.verify(scoring).deleteAll(List.of());
        order.verify(tasks).delete(task);
        order.verify(projects).save(project);
    }

    @Test
    void deleteSubTaskSkipsWithdrawalRepositoriesWhenNotInjected() {
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        Project project = new Project(); project.setId(1L); project.setStatus("pending_planner");
        SubTask task = new SubTask(); task.setId(2L); task.setName("包装设计");
        task.setStatus("pending"); task.setProject(project);
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(tasks.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        when(scoring.findBySubTaskId(2L)).thenReturn(List.of());
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 未注入退单/调账仓库：空集合守卫兜底，不 NPE 且其余清理照常执行
        ProjectService service = new ProjectService(projects, tasks, scoring, versions,
                mock(UserService.class), mock(ProductCategoryRepository.class), mock(IpOptionRepository.class),
                mock(SystemConfigRepository.class), mock(SyncQueueService.class), mock(FileArchiveService.class),
                mock(ProjectAccessService.class), mock(NotificationWorkflowService.class));

        service.deleteSubTask(1L, 2L);

        verify(versions).deleteBySubTaskIds(List.of(2L));
        verify(scoring).deleteAll(List.of());
        verify(tasks).delete(task);
        verify(projects).save(project);
    }

    private Map<String, Object> regularProjectBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "regular");
        body.put("currentRole", "admin");
        body.put("currentUserId", "admin-1");
        body.put("currentUser", "管理员");
        body.put("plannerId", "planner-1");
        body.put("productName", "测试产品");
        body.put("deadline", "2026-12-31");
        body.put("productRequirements", "设计要求");
        return body;
    }

    private static User plannerUser() {
        User planner = new User();
        planner.setUserId("planner-1");
        planner.setRole("planner");
        planner.setStatus("active");
        return planner;
    }
}
