package com.emie.designpm.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 将耗时的全员飞书广播移出 HTTP 请求线程，并提供可轮询的任务状态。 */
@Service
public class NotificationBroadcastJobService {

    private final NotificationTestService notificationTestService;
    private final ExecutorService executor;
    private final Map<String, BroadcastJob> jobs = new LinkedHashMap<>();
    private String runningJobId;

    public NotificationBroadcastJobService(NotificationTestService notificationTestService) {
        this(notificationTestService, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notification-broadcast");
            thread.setDaemon(true);
            return thread;
        }));
    }

    NotificationBroadcastJobService(NotificationTestService notificationTestService, ExecutorService executor) {
        this.notificationTestService = notificationTestService;
        this.executor = executor;
    }

    public synchronized Map<String, Object> start(String title, String content, String operatorUserId) {
        notificationTestService.validateTemporaryBroadcast(title, content);
        if (runningJobId != null) {
            BroadcastJob running = jobs.get(runningJobId);
            if (running != null && "running".equals(running.status)) {
                throw new IllegalStateException("已有全员通知正在发送，请等待当前任务完成");
            }
        }

        String jobId = UUID.randomUUID().toString();
        BroadcastJob job = new BroadcastJob(jobId, operatorUserId, "running", LocalDateTime.now());
        jobs.put(jobId, job);
        runningJobId = jobId;
        trimCompletedJobs();
        executor.submit(() -> execute(job, title, content));
        return job.toMap();
    }

    public synchronized Map<String, Object> status(String jobId) {
        BroadcastJob job = jobs.get(jobId);
        if (job == null) throw new IllegalArgumentException("发送任务不存在或已过期");
        return job.toMap();
    }

    private void execute(BroadcastJob job, String title, String content) {
        try {
            Map<String, Object> result = notificationTestService.sendTemporaryBroadcast(
                    title, content, job.operatorUserId);
            complete(job, "completed", result, null);
        } catch (Exception e) {
            complete(job, "failed", null, safeMessage(e));
        }
    }

    private synchronized void complete(BroadcastJob job, String status,
                                       Map<String, Object> result, String error) {
        job.status = status;
        job.result = result == null ? null : new LinkedHashMap<>(result);
        job.error = error;
        job.completedAt = LocalDateTime.now();
        if (job.jobId.equals(runningJobId)) runningJobId = null;
    }

    private void trimCompletedJobs() {
        while (jobs.size() >= 100) {
            String removable = jobs.entrySet().stream()
                    .filter(entry -> !"running".equals(entry.getValue().status))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (removable == null) return;
            jobs.remove(removable);
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "后台发送失败" :
                message.substring(0, Math.min(message.length(), 500));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private static class BroadcastJob {
        private final String jobId;
        private final String operatorUserId;
        private String status;
        private final LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private Map<String, Object> result;
        private String error;

        private BroadcastJob(String jobId, String operatorUserId, String status, LocalDateTime createdAt) {
            this.jobId = jobId;
            this.operatorUserId = operatorUserId;
            this.status = status;
            this.createdAt = createdAt;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("jobId", jobId);
            value.put("status", status);
            value.put("createdAt", createdAt);
            if (completedAt != null) value.put("completedAt", completedAt);
            if (result != null) value.put("result", new LinkedHashMap<>(result));
            if (error != null) value.put("error", error);
            return value;
        }
    }
}
