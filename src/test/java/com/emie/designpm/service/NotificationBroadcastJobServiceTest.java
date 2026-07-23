package com.emie.designpm.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationBroadcastJobServiceTest {

    @Test
    void broadcastRunsInBackgroundAndExposesFinalResult() throws Exception {
        NotificationTestService notifications = mock(NotificationTestService.class);
        doNothing().when(notifications).validateTemporaryBroadcast("更新", "今晚发布");
        when(notifications.sendTemporaryBroadcast("更新", "今晚发布", "admin-1"))
                .thenReturn(Map.of("total", 27, "delivered", 27, "failed", 0, "unbound", 0));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        NotificationBroadcastJobService jobs = new NotificationBroadcastJobService(notifications, executor);

        Map<String, Object> started = jobs.start("更新", "今晚发布", "admin-1");
        assertEquals("running", started.get("status"));
        assertNotNull(started.get("jobId"));

        Map<String, Object> status = awaitCompletion(jobs, (String) started.get("jobId"));
        assertEquals("completed", status.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) status.get("result");
        assertEquals(27, result.get("delivered"));

        jobs.shutdown();
    }

    private Map<String, Object> awaitCompletion(NotificationBroadcastJobService jobs, String jobId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Map<String, Object> status = jobs.status(jobId);
            if (!"running".equals(status.get("status"))) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("后台通知任务未按时完成");
    }
}
