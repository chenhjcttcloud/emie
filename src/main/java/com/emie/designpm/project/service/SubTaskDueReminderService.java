package com.emie.designpm.project.service;

import com.emie.designpm.entity.SubTask;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.project.repository.SubTaskRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
public class SubTaskDueReminderService {
    private static final Logger log = LoggerFactory.getLogger(SubTaskDueReminderService.class);
    private static final String TIME_ZONE = "Asia/Shanghai";
    private static final String EVENT_TYPE = "TASK_DUE_SOON";
    private static final PageRequest BATCH = PageRequest.ofSize(100);

    private final SubTaskRepository subTasks;
    private final NotificationWorkflowService workflow;
    private final ProjectNotifier notifier;

    public SubTaskDueReminderService(SubTaskRepository subTasks, NotificationWorkflowService workflow) {
        this.subTasks = subTasks;
        this.workflow = workflow;
        this.notifier = new ProjectNotifier(workflow);
    }

    /** 每天 09:00 起扫描当日到期任务，覆盖服务重启或当天新增的任务。 */
    @Scheduled(cron = "0 */10 9-23 * * *", zone = TIME_ZONE)
    public void remindDueTasks() {
        remindDueTasks(LocalDate.now(ZoneId.of(TIME_ZONE)));
    }

    void remindDueTasks(LocalDate dueDate) {
        String deadline = dueDate.toString();
        long afterId = 0;
        List<SubTask> batch;
        do {
            batch = subTasks.findDueReminderCandidates(deadline, afterId, BATCH);
            for (SubTask task : batch) {
                afterId = task.getId();
                remindAssignee(task, deadline);
            }
        } while (batch.size() == BATCH.getPageSize());
    }

    private void remindAssignee(SubTask task, String deadline) {
        String recipient = task.getDesignerId();
        UUID assigneeKey = UUID.nameUUIDFromBytes(recipient.getBytes(StandardCharsets.UTF_8));
        String key = EVENT_TYPE + ":" + task.getId() + ":" + deadline + ":" + assigneeKey;
        try {
            workflow.notifyUserOnce(
                    EVENT_TYPE,
                    recipient,
                    "sub_task",
                    task.getId(),
                    "system",
                    key,
                    notifier.context(task.getProject(), task, null, null));
        } catch (Exception e) {
            log.warn("子任务到期提醒创建失败: taskId={}, deadline={}", task.getId(), deadline, e);
        }
    }
}
