package com.emie.designpm.service;

import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.NotificationRepository;
import com.emie.designpm.repository.SubTaskRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 对超过30分钟仍未接单的子任务发送一次提醒，避免重复轰炸。 */
@Service
public class SubTaskAcceptanceReminderService {
    private static final String REMINDER_TITLE = "待办催办提醒";
    private final SubTaskRepository subTasks;
    private final NotificationRepository notifications;
    private final NotificationWorkflowService workflow;
    /** 本次应用启动时间作为提醒基线，避免首次上线把历史待接单任务集中轰炸。 */
    private LocalDateTime reminderBaseline;

    public SubTaskAcceptanceReminderService(SubTaskRepository subTasks,
                                            NotificationRepository notifications,
                                            NotificationWorkflowService workflow) {
        this.subTasks = subTasks;
        this.notifications = notifications;
        this.workflow = workflow;
    }

    @PostConstruct
    void initializeBaseline() {
        reminderBaseline = LocalDateTime.now();
    }

    @Scheduled(fixedDelayString = "${notification.task-acceptance-reminder-ms:300000}")
    @Transactional(readOnly = true)
    public void remindUnacceptedTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        for (SubTask task : subTasks.findTop100ByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                "pending", reminderBaseline, cutoff)) {
            if (task.getProject() == null || task.getId() == null) continue;
            String plannerName = safe(task.getProject().getPlannerName(), "未指定");
            String currentOwnerName = safe(task.getDesignerName(), "未指定");
            Map<String, String> context = Map.of(
                    "projectName", safe(task.getProject().getProductName(), "未命名项目"),
                    "taskName", safe(task.getName(), "未命名子任务"),
                    "deadline", safe(task.getPlannedDate(), "未设置"),
                    "actorName", "产品企划：" + plannerName,
                    "targetName", safe(task.getName(), "未命名子任务"),
                    "currentOwnerName", currentOwnerName);
            Set<String> recipients = new LinkedHashSet<>();
            add(recipients, task.getDesignerId());
            add(recipients, task.getProject().getPlannerId());
            for (String recipient : recipients) {
                if (notifications.existsByRecipientUserIdAndAggregateTypeAndAggregateIdAndTitleAndCreatedAtAfter(
                        recipient, "sub_task", task.getId(), REMINDER_TITLE, task.getCreatedAt())) continue;
                try {
                    workflow.notifyUser("TASK_REMINDER", recipient, "sub_task", task.getId(), "system", context);
                } catch (Exception ignored) {
                    // 失败由通知投递记录和重试机制承接，不影响扫描其他任务。
                }
            }
        }
    }

    private static void add(Set<String> recipients, String id) {
        if (id != null && !id.isBlank()) recipients.add(id);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
