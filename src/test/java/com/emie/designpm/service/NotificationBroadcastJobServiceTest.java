package com.emie.designpm.service;

import com.emie.designpm.entity.NotificationBroadcastJob;
import com.emie.designpm.repository.NotificationBroadcastJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationBroadcastJobServiceTest {

    private NotificationBroadcastJobService jobs;

    @AfterEach
    void shutdownExecutor() {
        if (jobs != null) jobs.shutdown();
    }

    @Test
    void broadcastRunsInBackgroundAndExposesPersistedFinalResult() throws Exception {
        NotificationTestService notifications = mock(NotificationTestService.class);
        doNothing().when(notifications).validateTemporaryBroadcast("更新", "今晚发布");
        when(notifications.sendTemporaryBroadcast("更新", "今晚发布", "admin-1"))
                .thenReturn(Map.of("total", 27, "delivered", 27, "failed", 0, "unbound", 0));
        jobs = service(notifications, inMemoryRepository());

        Map<String, Object> started = jobs.start("更新", "今晚发布", "admin-1");
        assertEquals("running", started.get("status"));
        assertNotNull(started.get("jobId"));

        Map<String, Object> status = awaitCompletion((String) started.get("jobId"));
        assertEquals("completed", status.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) status.get("result");
        assertEquals(27, result.get("delivered"));
    }

    @Test
    void databaseConstraintIsReportedAsExistingRunningBroadcast() {
        NotificationTestService notifications = mock(NotificationTestService.class);
        NotificationBroadcastJobRepository repository = mock(NotificationBroadcastJobRepository.class);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate running_slot"));
        jobs = service(notifications, repository);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> jobs.start("更新", "今晚发布", "admin-1"));
        assertEquals("已有全员通知正在发送，请等待当前任务完成", error.getMessage());
    }

    @Test
    void interruptedRunningJobBecomesSafeTerminalStateAndRemainsQueryableOnStartup() {
        NotificationBroadcastJobRepository repository = inMemoryRepository();
        NotificationBroadcastJob stale = new NotificationBroadcastJob("old-job", "admin-1", "old-instance",
                LocalDateTime.now().minusMinutes(5));
        repository.saveAndFlush(stale);
        jobs = service(mock(NotificationTestService.class), repository);
        jobs.recoverInterruptedJobsOnStartup();

        Map<String, Object> status = jobs.status("old-job");

        assertEquals("failed", status.get("status"));
        assertEquals(NotificationBroadcastJobService.INTERRUPTED_ERROR, status.get("error"));
        assertNotNull(status.get("completedAt"));
    }

    private NotificationBroadcastJobService service(NotificationTestService notifications,
                                                    NotificationBroadcastJobRepository repository) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return new NotificationBroadcastJobService(notifications, repository, new ObjectMapper(), executor);
    }

    private NotificationBroadcastJobRepository inMemoryRepository() {
        NotificationBroadcastJobRepository repository = mock(NotificationBroadcastJobRepository.class);
        Map<String, NotificationBroadcastJob> store = new ConcurrentHashMap<>();
        when(repository.saveAndFlush(any(NotificationBroadcastJob.class))).thenAnswer(invocation -> {
            NotificationBroadcastJob job = invocation.getArgument(0);
            store.put(job.getId(), job);
            return job;
        });
        when(repository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(repository.failInterruptedJobs(any(LocalDateTime.class), anyString())).thenAnswer(invocation -> {
            LocalDateTime now = invocation.getArgument(0);
            String error = invocation.getArgument(1);
            int changed = 0;
            for (NotificationBroadcastJob job : store.values()) {
                if ("running".equals(job.getStatus())) {
                    job.setStatus("failed");
                    job.setError(error);
                    job.setCompletedAt(now);
                    changed++;
                }
            }
            return changed;
        });
        when(repository.completeRunningJob(anyString(), anyString(), anyString(), any(), any(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    NotificationBroadcastJob job = store.get(invocation.getArgument(0));
                    if (job == null || !"running".equals(job.getStatus())
                            || !job.getOwnerInstanceId().equals(invocation.getArgument(1))) return 0;
                    job.setStatus(invocation.getArgument(2));
                    job.setResultJson(invocation.getArgument(3));
                    job.setError(invocation.getArgument(4));
                    job.setCompletedAt(invocation.getArgument(5));
                    return 1;
                });
        when(repository.failOwnedJobs(anyString(), any(LocalDateTime.class), anyString())).thenReturn(0);
        return repository;
    }

    private Map<String, Object> awaitCompletion(String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Map<String, Object> status = jobs.status(jobId);
            if (!"running".equals(status.get("status"))) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("后台通知任务未按时完成");
    }
}
