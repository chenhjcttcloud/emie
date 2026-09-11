package com.emie.designpm.project.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 项目/子任务通知的封装：构造通知上下文、吞掉通知失败不阻断业务。
 * 原先 ProjectService 和 DefaultSubTaskCommandService 各有一份相同的
 * notificationContext / safeNotify / safeNotifyAfterCommit。
 */
final class ProjectNotifier {

    private static final Logger log = LoggerFactory.getLogger(ProjectNotifier.class);

    private final NotificationWorkflowService notificationWorkflowService;

    ProjectNotifier(NotificationWorkflowService notificationWorkflowService) {
        this.notificationWorkflowService = notificationWorkflowService;
    }

    Map<String, String> context(Project project, SubTask task, String actor, String reason) {
        Map<String, String> context = new HashMap<>();
        context.put("projectName", project.getProductName());
        context.put("deadline", task != null ? task.getPlannedDate() : project.getDeadline());
        context.put("actorName", actor == null || actor.isBlank() ? "系统" : actor);
        context.put("projectLink", "/?projectId=" + project.getId());
        if (task != null) {
            context.put("taskName", task.getName());
            context.put("taskLink", "/?projectId=" + project.getId() + "&taskId=" + task.getId());
        }
        if (reason != null && !reason.isBlank()) context.put("reason", reason);
        return context;
    }

    void safeNotify(
            String eventType,
            String recipientUserId,
            String aggregateType,
            Long aggregateId,
            String actorUserId,
            Map<String, String> context) {
        try {
            notificationWorkflowService.notifyUser(
                    eventType, recipientUserId, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("通知创建失败但业务操作继续: eventType={}, aggregate={}#{}", eventType, aggregateType, aggregateId, e);
        }
    }

    void safeNotifyAfterCommit(
            String eventType,
            String recipientUserId,
            String aggregateType,
            Long aggregateId,
            String actorUserId,
            Map<String, String> context) {
        try {
            notificationWorkflowService.notifyUserAfterCommit(
                    eventType, recipientUserId, aggregateType, aggregateId, actorUserId, context);
        } catch (Exception e) {
            log.error("提交后通知注册失败但业务操作继续: eventType={}, aggregate={}#{}", eventType, aggregateType, aggregateId, e);
        }
    }
}
