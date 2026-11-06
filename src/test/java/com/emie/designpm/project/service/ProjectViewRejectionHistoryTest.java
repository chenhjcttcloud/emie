package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskRejectionCycle;
import com.emie.designpm.project.repository.SubTaskRejectionCycleRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProjectViewRejectionHistoryTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 10, 0);

    @Test
    void projectAndMyTasksReadDurableCyclesAndLinkEachRejectionToItsOwnDeliveryRound() {
        Project project = new Project();
        project.setId(1L);
        project.setProductName("包装项目");
        project.setType("regular");
        project.setCreatedAt(AT.minusDays(2));
        project.setUpdatedAt(AT);
        SubTask task = new SubTask();
        task.setId(11L);
        task.setName("包装稿");
        task.setProject(project);
        task.setStatus("submitted_for_review");
        task.setDesignerId("d1");
        task.setCreatedAt(AT.minusDays(1));
        task.setSubmittedForReviewAt(at(600));
        task.setDeliverables("最后一轮成果");
        project.getTasks().add(task);

        var old = new ActivityLog("子任务驳回：包装稿（意见：旧版反馈）", "企划", "planner", project);
        old.setId(10L);
        old.setTime(AT.minusDays(1));
        old.setBeforeData("{\"deliverables\":\"旧版交付留档\"}");
        var duplicate = new ActivityLog(
                "子任务驳回：包装稿（意见：重复审计旧文本）",
                "企划",
                "planner",
                project,
                "sub_task",
                11L,
                "{}",
                "{\"rejectionCycleId\":201}",
                "");
        duplicate.setId(11L);
        duplicate.setTime(at(200));
        var unrelated =
                new ActivityLog("子任务驳回：包装稿（意见：其他任务）", "企划", "planner", project, "sub_task", 99L, "{}", "{}", "");
        unrelated.setId(12L);
        unrelated.setTime(at(250));

        var first = cycle(task, 201, 1, 200, "请更换颜色");
        first.setCreatedBonusRequestId("same-bonus");
        first.setPriorReviewSnapshotJson(AuditJson.toJson(Map.of(
                "submission", Map.of("deliveryVersionId", 101L, "deliverables", "首轮快照"),
                "rejection", Map.of("withPoints", true, "points", 3.5, "pointRuleCode", "R1"))));
        var cancelled = cycle(task, 202, 2, 350, "误驳回");
        cancelled.setStatus("CANCELLED");
        cancelled.setCancelledAt(at(360));
        cancelled.setCancelledByName("企划乙");
        var second = cycle(task, 203, 3, 500, "请补充尺寸");
        second.setPriorReviewSnapshotJson("{}");
        second.setAttachmentsJson("[{\"name\":\"尺寸要求.pdf\"}]");
        var v1 = version(101L, 1, 100, "initial", "首轮", "");
        var v2 = version(102L, 2, 300, "revision", "换色版", "same-bonus");
        var correction = version(103L, 3, 400, "correction", "换色更正版", "same-bonus");
        var v3 = version(104L, 4, 600, "revision", "尺寸补充版", "same-bonus");

        var projects = mock(ProjectService.class);
        var logs = mock(ActivityLogRepository.class);
        var tasks = mock(SubTaskRepository.class);
        var cycles = mock(SubTaskRejectionCycleRepository.class);
        var commands = mock(SubTaskCommandService.class);
        var access = mock(ProjectAccessService.class);
        when(projects.computeProjectStatus(project)).thenReturn("in_progress");
        when(tasks.findMySubTasks("d1")).thenReturn(List.of(task));
        when(access.departmentTaskUserIds("planner", "p1")).thenReturn(List.of("d1"));
        when(tasks.findDepartmentSubTasks(List.of("d1"))).thenReturn(List.of(task));
        when(logs.findSubTaskRejectionHistory(List.of(11L), List.of(1L)))
                .thenReturn(List.of(old, duplicate, unrelated));
        when(cycles.findBySubTaskIdInOrderBySubTaskIdAscSequenceNoAsc(List.of(11L)))
                .thenReturn(List.of(first, cancelled, second));
        when(commands.getDeliveryVersionsByTaskIds(List.of(11L)))
                .thenReturn(Map.of(11L, List.of(v3, correction, v2, v1)));
        var view = new ProjectViewSupport(
                projects,
                mock(ScoringRepository.class),
                logs,
                tasks,
                access,
                mock(ProjectWorkflowService.class),
                commands);
        ReflectionTestUtils.setField(view, "rejectionCycleRepository", cycles);

        // The general project activity feed has already dropped all four events.
        var details = view.toDetailWithLogs(project, List.of()).getTasks().getFirst();
        var mine = view.getMySubTasks(new AuthSession("d1", "designer", "设计师")).getFirst();
        var department = view.getDepartmentSubTasks(new AuthSession("p1", "planner", "产品企划"))
                .getFirst();
        assertEquals(at(600).format(ProjectViewSupport.DTF), mine.get("submittedForReviewAt"));
        assertEquals(mine.get("submittedForReviewAt"), department.get("submittedForReviewAt"));
        var records = details.getRejectionRecords();
        var deliveries = details.getDeliveryVersions();
        assertEquals(records, mine.get("rejectionRecords"));
        assertEquals(deliveries, mine.get("deliveryVersions"));
        assertEquals(deliveries, department.get("deliveryVersions"));
        assertEquals(
                List.of("旧版反馈", "请更换颜色", "误驳回", "请补充尺寸"),
                records.stream().map(r -> r.get("reason")).toList());
        assertEquals(
                List.of(1, 2, 3, 4),
                records.stream().map(r -> r.get("attemptNo")).toList());
        assertEquals("旧版交付留档", records.getFirst().get("deliverables"));
        assertEquals(101L, records.get(1).get("rejectedDeliveryVersionId"));
        assertEquals("首轮快照", records.get(1).get("deliverables"));
        assertEquals(3.5, records.get(1).get("points"));
        assertEquals("same-bonus", records.get(1).get("pointRequestId"));
        assertEquals(true, records.get(2).get("cancelled"));
        assertEquals("企划乙", records.get(2).get("cancelledByName"));
        assertEquals(103L, records.get(3).get("rejectedDeliveryVersionId"));
        assertEquals("换色更正版", records.get(3).get("deliverables"));
        assertEquals("[{\"name\":\"尺寸要求.pdf\"}]", records.get(3).get("rejectionAttachmentsJson"));
        assertEquals(203L, deliveries.get(0).get("rejectionCycleId"));
        assertEquals("请补充尺寸", deliveries.get(0).get("rejectionReason"));
        assertEquals(201L, deliveries.get(1).get("rejectionCycleId"));
        assertEquals(201L, deliveries.get(2).get("rejectionCycleId"));
        assertEquals("请更换颜色", deliveries.get(2).get("rejectionReason"));
        assertEquals("same-bonus", deliveries.get(0).get("pointRequestId"));
        assertEquals("same-bonus", deliveries.get(2).get("pointRequestId"));
        assertNull(deliveries.get(3).get("rejectionCycleId"));
        assertFalse(v3.containsKey("rejectionCycleId"));
        verify(logs, times(3)).findSubTaskRejectionHistory(List.of(11L), List.of(1L));
        verify(logs, never()).findProjectHistory(anyLong());
    }

    private static SubTaskRejectionCycle cycle(SubTask task, long id, int sequence, int millis, String reason) {
        var cycle = new SubTaskRejectionCycle();
        cycle.setSubTask(task);
        cycle.setId(id);
        cycle.setSequenceNo(sequence);
        cycle.setRejectedAt(at(millis));
        cycle.setRejectedByName("企划甲");
        cycle.setRejectionRole("planner");
        cycle.setReason(reason);
        cycle.setRequiredCompletionDate("2026-11-06");
        return cycle;
    }

    private static Map<String, Object> version(
            long id, int no, int millis, String type, String note, String requestId) {
        return Map.of(
                "id",
                id,
                "versionNo",
                no,
                "submittedAt",
                at(millis).toString(),
                "submissionType",
                type,
                "deliverables",
                note,
                "pointRequestId",
                requestId);
    }

    private static LocalDateTime at(int millis) {
        return AT.plusNanos(millis * 1_000_000L);
    }
}
