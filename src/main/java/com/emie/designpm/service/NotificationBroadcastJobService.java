package com.emie.designpm.service;

import com.emie.designpm.entity.NotificationBroadcastJob;
import com.emie.designpm.repository.NotificationBroadcastJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 将耗时的全员飞书广播移出 HTTP 请求线程，并持久化可轮询的任务状态。 */
@Service
public class NotificationBroadcastJobService {

    static final String INTERRUPTED_ERROR = "应用已停止，广播任务未自动重试，请重新确认后发送";

    private final NotificationTestService notificationTestService;
    private final NotificationBroadcastJobRepository repository;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final String instanceId = UUID.randomUUID().toString();
    private volatile String runningJobId;

    @Autowired
    public NotificationBroadcastJobService(NotificationTestService notificationTestService,
                                           NotificationBroadcastJobRepository repository,
                                           ObjectMapper objectMapper) {
        this(notificationTestService, repository, objectMapper,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "notification-broadcast");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    NotificationBroadcastJobService(NotificationTestService notificationTestService,
                                    NotificationBroadcastJobRepository repository,
                                    ObjectMapper objectMapper, ExecutorService executor) {
        this.notificationTestService = notificationTestService;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public synchronized Map<String, Object> start(String title, String content, String operatorUserId) {
        notificationTestService.validateTemporaryBroadcast(title, content);
        LocalDateTime now = LocalDateTime.now();
        String jobId = UUID.randomUUID().toString();
        NotificationBroadcastJob job = new NotificationBroadcastJob(
                jobId, operatorUserId, instanceId, now);
        try {
            repository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("已有全员通知正在发送，请等待当前任务完成");
        }
        runningJobId = jobId;
        try {
            executor.submit(() -> execute(jobId, title, content, operatorUserId));
        } catch (RuntimeException e) {
            repository.completeRunningJob(jobId, instanceId, "failed", null,
                    "后台执行器不可用，请重新发送", LocalDateTime.now());
            runningJobId = null;
            throw e;
        }
        return toMap(job);
    }

    public Map<String, Object> status(String jobId) {
        NotificationBroadcastJob job = repository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("发送任务不存在或已过期"));
        return toMap(job);
    }

    private void execute(String jobId, String title, String content, String operatorUserId) {
        try {
            Map<String, Object> result = notificationTestService.sendTemporaryBroadcast(title, content, operatorUserId);
            complete(jobId, "completed", result, null);
        } catch (Exception e) {
            complete(jobId, "failed", null, safeMessage(e));
        }
    }

    private void complete(String jobId, String status, Map<String, Object> result, String error) {
        repository.completeRunningJob(jobId, instanceId, status, writeResult(result), error, LocalDateTime.now());
        if (jobId.equals(runningJobId)) runningJobId = null;
    }

    @PostConstruct
    void recoverInterruptedJobsOnStartup() {
        // 当前部署模型为单应用实例；进程启动意味着旧 worker 已不存在，绝不自动重发。
        repository.failInterruptedJobs(LocalDateTime.now(), INTERRUPTED_ERROR);
    }

    private String writeResult(Map<String, Object> result) {
        if (result == null) return null;
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存广播结果", e);
        }
    }

    private Map<String, Object> readResult(String resultJson) {
        if (resultJson == null) return null;
        try {
            return objectMapper.readValue(resultJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取广播结果", e);
        }
    }

    private Map<String, Object> toMap(NotificationBroadcastJob job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobId", job.getId());
        value.put("status", job.getStatus());
        value.put("createdAt", job.getCreatedAt());
        if (job.getCompletedAt() != null) value.put("completedAt", job.getCompletedAt());
        Map<String, Object> result = readResult(job.getResultJson());
        if (result != null) value.put("result", result);
        if (job.getError() != null) value.put("error", job.getError());
        return value;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "后台发送失败" :
                message.substring(0, Math.min(message.length(), 500));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        repository.failOwnedJobs(instanceId, LocalDateTime.now(), INTERRUPTED_ERROR);
    }
}
