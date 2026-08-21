package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 业务事件的统一通知入口：先创建站内必达通知，再尝试飞书投递并保留审计。 */
@Service
public class NotificationWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorkflowService.class);
    private final NotificationOutboxService outbox;
    private final NotificationTemplateService templates;
    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationAuditLogRepository audits;
    private final UserRepository users;
    private final SystemConfigRepository configs;
    private final FeishuBaseService feishu;
    private final NotificationRecipientRouter recipientRouter;
    private final TransactionTemplate requiresNew;

    public NotificationWorkflowService(NotificationOutboxService outbox, NotificationTemplateService templates,
            NotificationRepository notifications, NotificationDeliveryRepository deliveries,
            NotificationAuditLogRepository audits, UserRepository users, SystemConfigRepository configs, FeishuBaseService feishu,
            NotificationRecipientRouter recipientRouter,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.outbox = outbox; this.templates = templates; this.notifications = notifications; this.deliveries = deliveries;
        this.audits = audits; this.users = users; this.configs = configs; this.feishu = feishu; this.recipientRouter = recipientRouter;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void notifyUser(String eventType, String recipientUserId, String aggregateType, Long aggregateId,
                           String actorUserId, Map<String, String> context) {
        if (recipientUserId == null || recipientUserId.isBlank()) return;
        String routedRecipientUserId = recipientRouter.route(recipientUserId);
        if (routedRecipientUserId == null || routedRecipientUserId.isBlank()
                || (!recipientRouter.isTestOverrideEnabled() && routedRecipientUserId.equals(actorUserId))) return;
        if (aggregateId == null) {
            throw new IllegalArgumentException("通知业务对象 ID 不能为空");
        }
        NotificationTemplateService.Template t = templates.render(eventType, context);
        NotificationBundle bundle = requiresNew.execute(status -> {
            NotificationEvent event = outbox.publish(eventType, aggregateType, aggregateId, 1, actorUserId,
                    eventType + ":" + aggregateType + ":" + aggregateId + ":" + UUID.randomUUID(), t.content());
            Notification n = notifications.save(Notification.builder().eventId(event.getId()).recipientUserId(routedRecipientUserId)
                    .category("workflow").priority(t.priority()).mandatory(t.mandatory()).title(t.title()).content(t.content())
                    .deepLink(t.deepLink()).aggregateType(aggregateType).aggregateId(aggregateId).status("unread").createdAt(LocalDateTime.now()).build());
            NotificationDelivery inApp = deliveries.save(NotificationDelivery.builder().notificationId(n.getId()).channel("in_app")
                    .status("delivered").retryCount(0).deliveredAt(LocalDateTime.now()).build());
            audit(event, n, inApp, "in_app_delivered", actorUserId, "工作流站内通知已创建");
            return new NotificationBundle(event, n);
        });
        if (!enabled("notification.feishuEnabled")) return;
        users.findByUserId(routedRecipientUserId).ifPresent(user -> sendFeishu(bundle.event(), bundle.notification(), user, t, actorUserId));
    }

    private record NotificationBundle(NotificationEvent event, Notification notification) {}

    /** 业务数据提交成功后再创建通知，避免通知先于项目/任务事务提交或业务回滚后仍被发出。 */
    public void notifyUserAfterCommit(String eventType, String recipientUserId, String aggregateType, Long aggregateId,
                                      String actorUserId, Map<String, String> context) {
        Runnable action = () -> {
            try {
                notifyUser(eventType, recipientUserId, aggregateType, aggregateId, actorUserId, context);
            } catch (Exception e) {
                // 通知失败由投递记录承接，不能反向影响已经提交的业务事务。
                log.error("提交后通知创建失败: eventType={}, aggregate={}#{}, recipient={}",
                        eventType, aggregateType, aggregateId, recipientUserId, e);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 向指定角色的全部有效用户发送同一业务通知，并为每位收件人保留独立审计记录。 */
    public void notifyRole(String eventType, String role, String aggregateType, Long aggregateId,
                           String actorUserId, Map<String, String> context) {
        List<String> recipients = users.findByRole(role).stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .map(User::getUserId)
                .toList();
        recipientRouter.routeAll(recipients)
                .forEach(id -> notifyUser(eventType, id, aggregateType, aggregateId, actorUserId, context));
    }

    /** 与 {@link #notifyRole} 一致，但等业务事务提交成功后才创建通知。 */
    public void notifyRoleAfterCommit(String eventType, String role, String aggregateType, Long aggregateId,
                                      String actorUserId, Map<String, String> context) {
        List<String> recipients = users.findByRole(role).stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .map(User::getUserId)
                .toList();
        recipientRouter.routeAll(recipients)
                .forEach(id -> notifyUserAfterCommit(eventType, id, aggregateType, aggregateId, actorUserId, context));
    }

    private void sendFeishu(NotificationEvent event, Notification n, User user, NotificationTemplateService.Template t, String actor) {
        LocalDateTime now = LocalDateTime.now();
        // 此方法会在业务事务提交后运行。投递台账必须使用独立事务，否则会在 afterCommit
        // 阶段静默丢失，导致实际调用了飞书却没有可重试、可审计的失败记录。
        NotificationDelivery d = requiresNew.execute(status -> deliveries.save(NotificationDelivery.builder()
                .notificationId(n.getId()).channel("feishu").status("pending").retryCount(0).createdAt(now)
                .firstAttemptAt(now).lastAttemptAt(now).cardPayload(t.feishuCardJson()).build()));
        if (user.getFeishuOpenId() == null || user.getFeishuOpenId().isBlank()) {
            d.setStatus("blocked");
            d.setFailedAt(LocalDateTime.now());
            d.setErrorMsg("收件人未绑定飞书 Open ID");
            d.setNextRetryAt(null);
            saveFeishuOutcome(event, n, d, "feishu_blocked", actor, "收件人未绑定飞书 Open ID，等待绑定后手动重试");
            return;
        }
        try {
            d.setExternalMessageId(feishu.sendInteractiveMessage(user.getFeishuOpenId(), t.feishuCardJson())); d.setStatus("delivered"); d.setDeliveredAt(LocalDateTime.now());
            saveFeishuOutcome(event, n, d, "feishu_delivered", actor, "工作流飞书通知已投递");
        } catch (Exception e) {
            String cardError = limit(e.getMessage());
            try {
                d.setExternalMessageId(feishu.sendTextMessage(user.getFeishuOpenId(), t.content()));
                d.setStatus("delivered"); d.setDeliveredAt(LocalDateTime.now());
                d.setErrorMsg("卡片发送失败，已降级为文本：" + cardError);
                saveFeishuOutcome(event, n, d, "feishu_text_fallback_delivered", actor, "卡片解析失败，已降级为文本通知：" + cardError);
            } catch (Exception fallbackError) {
                d.setStatus("failed");
                d.setFailedAt(LocalDateTime.now());
                d.setRetryCount((d.getRetryCount() == null ? 0 : d.getRetryCount()) + 1);
                d.setNextRetryAt(LocalDateTime.now().plusSeconds(30));
                d.setErrorMsg(limit(cardError + "；文本降级也失败：" + fallbackError.getMessage()));
                saveFeishuOutcome(event, n, d, "feishu_failed", actor, "卡片和文本通知均失败：" + limit(d.getErrorMsg()));
            }
        }
    }
    private void saveFeishuOutcome(NotificationEvent event, Notification notification, NotificationDelivery delivery,
                                   String action, String actor, String detail) {
        requiresNew.execute(status -> {
            NotificationDelivery saved = deliveries.save(delivery);
            audit(event, notification, saved, action, actor, detail);
            return null;
        });
    }
    private boolean enabled(String key) {
        return configs.findByConfigKey(key).map(SystemConfig::getConfigValue).map("true"::equalsIgnoreCase)
                // 兼容旧环境：早期版本只保存了飞书登录开关，尚未初始化独立的通知开关。
                .orElseGet(() -> "notification.feishuEnabled".equals(key)
                        && configs.findByConfigKey("feishu.enabled").map(SystemConfig::getConfigValue)
                        .map("true"::equalsIgnoreCase).orElse(false));
    }
    private void audit(NotificationEvent e, Notification n, NotificationDelivery d, String action, String actor, String detail) { audits.save(NotificationAuditLog.builder().eventId(e.getId()).notificationId(n.getId()).deliveryId(d.getId()).action(action).operatorUserId(actor).detail(detail).createdAt(LocalDateTime.now()).build()); }
    private String limit(String s) { return s == null ? "未知错误" : s.substring(0, Math.min(s.length(), 1000)); }
}
