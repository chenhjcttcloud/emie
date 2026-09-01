package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.entity.SyncQueue;
import com.emie.designpm.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SyncWorkerReviewSummaryTest {

    @Test
    void latestDeliveryDateFallsBackToNewestNonBlankVersion() {
        SubTaskDeliveryVersion latest = new SubTaskDeliveryVersion();
        latest.setActualDate(null);
        SubTaskDeliveryVersion previous = new SubTaskDeliveryVersion();
        previous.setActualDate("2026-08-31");

        assertEquals("2026-08-31", SyncWorker.latestDeliveryActualDate(List.of(latest, previous)));
    }

    @Test
    void projectAndSubTaskFlowSummariesExposeCurrentProgress() {
        assertEquals("打样送审（审核中）",
                SyncWorker.projectFlowLabel("in_progress", "sample_review", "under_review"));
        assertEquals("已完成", SyncWorker.projectFlowLabel("completed", "bulk", "completed"));

        SubTask completed = taskWithStatus("completed");
        SubTask reviewing = taskWithStatus("submitted_for_review");
        SubTask active = taskWithStatus("accepted");
        SubTask pending = taskWithStatus("pending");

        assertEquals("已完成 1/4｜送审中 1｜进行中 1｜待认领 1",
                SyncWorker.taskProgressSummary(List.of(completed, reviewing, active, pending)));
    }

    @Test
    void subTaskSyncContainsBothReviewScoresAndWeightedFinalScore() throws Exception {
        SyncQueueRepository queueRepository = mock(SyncQueueRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        SubTaskRepository taskRepository = mock(SubTaskRepository.class);
        ScoringRepository scoringRepository = mock(ScoringRepository.class);
        ActivityLogRepository logRepository = mock(ActivityLogRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        FeishuBaseService feishu = mock(FeishuBaseService.class);

        SyncQueue item = SyncQueue.builder()
                .entityType("sub_task")
                .entityId(11L)
                .action("update")
                .status("pending")
                .retryCount(0)
                .build();
        when(queueRepository.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("pending"), eq("pending"), any())).thenReturn(List.of(item));

        Project project = new Project();
        project.setId(1L);
        project.setType("channel_custom");
        SubTask task = new SubTask();
        task.setId(11L);
        task.setName("包装设计");
        task.setStatus("completed");
        task.setProject(project);
        when(taskRepository.findById(11L)).thenReturn(Optional.of(task));
        SubTaskDeliveryVersion delivery = new SubTaskDeliveryVersion();
        delivery.setActualDate("2026-08-31");
        when(versions.findFirstBySubTaskIdAndActualDateIsNotNullOrderByVersionNoDesc(11L))
                .thenReturn(Optional.of(delivery));

        ScoringRecord first = review(task, "planner", "first", 80, 0.4);
        ScoringRecord second = review(task, "sales", "second", 90, 0.6);
        when(scoringRepository.findBySubTaskId(11L)).thenReturn(List.of(first, second));

        SyncWorker worker = new SyncWorker(
                queueRepository, projectRepository, taskRepository, scoringRepository,
                logRepository, feishu, mock(SyncQueueService.class), mock(SystemConfigRepository.class),
                versions, null);
        worker.processQueue();

        ArgumentCaptor<FeishuBaseService.SubTaskSyncData> captor =
                ArgumentCaptor.forClass(FeishuBaseService.SubTaskSyncData.class);
        verify(feishu).syncSubTask(captor.capture());
        FeishuBaseService.SubTaskSyncData data = captor.getValue();
        assertEquals("planner", data.firstReviewRole());
        assertEquals(80, data.firstReviewScore());
        assertEquals("sales", data.secondReviewRole());
        assertEquals(90, data.secondReviewScore());
        assertEquals(86.0, data.finalReviewScore());
        assertEquals("2026-08-31", data.actualDate());
        assertEquals("done", item.getStatus());
    }

    private ScoringRecord review(SubTask task, String role, String stage, int score, double weight) {
        ScoringRecord record = new ScoringRecord();
        record.setRole(role);
        record.setReviewStage(stage);
        record.setReviewStatus("approved");
        record.setReviewerName("planner".equals(role) ? "企划甲" : "销售甲");
        record.setScore(score);
        record.setWeight(weight);
        record.setSubTask(task);
        return record;
    }

    private SubTask taskWithStatus(String status) {
        SubTask task = new SubTask();
        task.setStatus(status);
        return task;
    }
}
