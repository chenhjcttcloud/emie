package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.project.repository.SubTaskRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(
        properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"},
        showSql = false)
@ContextConfiguration(classes = SubTaskDueReminderServiceTest.Persistence.class)
class SubTaskDueReminderServiceTest {
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 11, 6);

    @Configuration
    @EnableJpaRepositories(basePackageClasses = SubTaskRepository.class)
    @EntityScan(basePackageClasses = Project.class)
    static class Persistence {}

    @Autowired
    EntityManager em;

    @Autowired
    SubTaskRepository subTasks;

    @Test
    void scansAllBatchesForAssignedActionableTasksDueTodayEvenWhenOneReminderFails() {
        Project active = project("in_progress");
        List<SubTask> expected = new ArrayList<>();
        expected.add(task(active, "pending", "designer-1", DUE_DATE));
        expected.add(task(active, "rejected", "designer-2", DUE_DATE));
        expected.add(task(active, "revision_requested", "designer-3", DUE_DATE));
        for (int index = 0; index < 98; index++) {
            expected.add(task(active, "accepted", "designer-4", DUE_DATE));
        }
        for (String state : List.of("submitted_for_review", "delivered", "planner_approved", "completed")) {
            task(active, state, "excluded", DUE_DATE);
        }
        task(active, "pending", null, DUE_DATE);
        task(active, "pending", "  ", DUE_DATE);
        task(active, "pending", "excluded", DUE_DATE).setAllocationStatus("market_open");
        task(active, "pending", "excluded", DUE_DATE).setAllocationStatus("withdrawn");
        task(active, "accepted", "excluded", DUE_DATE.minusDays(1));
        task(active, "accepted", "excluded", DUE_DATE.plusDays(1));
        for (String state : List.of("paused", "pending_terminate", "terminated", "completed")) {
            task(project(state), "accepted", "excluded", DUE_DATE);
        }
        em.flush();
        em.clear();

        NotificationWorkflowService workflow = mock(NotificationWorkflowService.class);
        doThrow(new IllegalStateException("模拟通知失败"))
                .when(workflow)
                .notifyUserOnce(
                        eq("TASK_DUE_SOON"),
                        eq("designer-1"),
                        eq("sub_task"),
                        eq(expected.getFirst().getId()),
                        eq("system"),
                        anyString(),
                        anyMap());
        new SubTaskDueReminderService(subTasks, workflow).remindDueTasks(DUE_DATE);

        ArgumentCaptor<Long> taskIds = ArgumentCaptor.forClass(Long.class);
        verify(workflow, times(expected.size()))
                .notifyUserOnce(
                        eq("TASK_DUE_SOON"),
                        anyString(),
                        eq("sub_task"),
                        taskIds.capture(),
                        eq("system"),
                        anyString(),
                        anyMap());
        assertEquals(expected.stream().map(SubTask::getId).toList(), taskIds.getAllValues());
    }

    @Test
    void repeatsUseSameKeyAndAnEditedDeadlineAndOwnerUseCurrentTaskContext() {
        Project project = project("in_progress");
        SubTask task = task(project, "pending", "original-owner", DUE_DATE);
        NotificationWorkflowService workflow = mock(NotificationWorkflowService.class);
        SubTaskDueReminderService service = new SubTaskDueReminderService(subTasks, workflow);
        service.remindDueTasks(DUE_DATE);
        service.remindDueTasks(DUE_DATE);
        verify(workflow, times(2))
                .notifyUserOnce(
                        "TASK_DUE_SOON",
                        "original-owner",
                        "sub_task",
                        task.getId(),
                        "system",
                        reminderKey(task, DUE_DATE, "original-owner"),
                        context(task, DUE_DATE));

        LocalDate newDeadline = DUE_DATE.plusDays(1);
        task.setDesignerId("current-owner");
        task.setAssigneeRole("supplychain");
        task.setPlannedDate(newDeadline.toString());
        service.remindDueTasks(DUE_DATE);
        service.remindDueTasks(newDeadline);
        verify(workflow)
                .notifyUserOnce(
                        "TASK_DUE_SOON",
                        "current-owner",
                        "sub_task",
                        task.getId(),
                        "system",
                        reminderKey(task, newDeadline, "current-owner"),
                        context(task, newDeadline));
        verifyNoMoreInteractions(workflow);
    }

    private Project project(String status) {
        Project project = new Project();
        project.setType("regular");
        project.setStatus(status);
        project.setProductName("到期提醒回归项目");
        project.setDeadline(DUE_DATE.plusDays(7).toString());
        project.setPlannerId("planner-1");
        em.persist(project);
        return project;
    }

    private SubTask task(Project project, String status, String owner, LocalDate date) {
        SubTask task = new SubTask();
        task.setProject(project);
        task.setName("到期提醒回归子任务");
        task.setStatus(status);
        task.setDesignerId(owner);
        task.setPlannedDate(date.toString());
        em.persist(task);
        return task;
    }

    private static String reminderKey(SubTask task, LocalDate deadline, String recipient) {
        return "TASK_DUE_SOON:" + task.getId() + ":" + deadline + ":"
                + UUID.nameUUIDFromBytes(recipient.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> context(SubTask task, LocalDate deadline) {
        Long projectId = task.getProject().getId();
        return Map.of(
                "taskName",
                task.getName(),
                "projectName",
                task.getProject().getProductName(),
                "actorName",
                "系统",
                "deadline",
                deadline.toString(),
                "taskLink",
                "/?projectId=" + projectId + "&taskId=" + task.getId(),
                "projectLink",
                "/?projectId=" + projectId);
    }
}
