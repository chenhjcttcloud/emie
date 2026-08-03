package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Collection;
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

    public NotificationWorkflowService(NotificationOutboxService outbox, NotificationTemplateService templates,
            NotificationRepository notifications, NotificationDeliveryRepository deliveries,
            NotificationAuditLogRepository audits, UserRepository users, SystemConfigRepository configs, FeishuBaseService feishu) {
        this.outbox = outbox; this.templates = templates; this.notifications = notifications; this.deliveries = deliveries;
        this.audits = audits; this.users = users; this.configs = configs; this.feishu = feishu;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyUser(String eventType, String recipientUserId, String aggregateType, Long aggregateId,
                           String actorUserId, Map<String, String> context) {
        if (recipientUserId == null || recipientUserId.isBlank() || recipientUserId.equals(actorUserId)) return;
        if (aggregateId == null) {
            throw new IllegalArgumentException("通知业务对象 ID 不能为空");
        }
        NotificationTemplateService.Template t = templates.render(eventType, context);
        NotificationEvent event = outbox.publish(eventType, aggregateType, aggregateId, 1, actorUserId,
                eventType + ":" + aggregateType + ":" + aggregateId + ":" + UUID.randomUUID(), t.content());
        Notification n = notifications.save(Notification.builder().eventId(event.getId()).recipientUserId(recipientUserId)
                .category("workflow").priority(t.priority()).mandatory(t.mandatory()).title(t.title()).content(t.content())
                .deepLink(t.deepLink()).aggregateType(aggregateType).aggregateId(aggregateId).status("unread").createdAt(LocalDateTime.now()).build());
        NotificationDelivery inApp = deliveries.save(NotificationDelivery.builder().notificationId(n.getId()).channel("in_app")
                .status("delivered").retryCount(0).deliveredAt(LocalDateTime.now()).build());
        audit(event, n, inApp, "in_app_delivered", actorUserId, "工作流站内通知已创建");
        if (!enabled("notification.feishuEnabled")) return;
        users.findByUserId(recipientUserId).ifPresent(user -> sendFeishu(event, n, user, t, actorUserId));
    }

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
        users.findByRole(role).stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .map(User::getUserId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(id -> notifyUser(eventType, id, aggregateType, aggregateId, actorUserId, context));
    }

    private void sendFeishu(NotificationEvent event, Notification n, User user, NotificationTemplateService.Template t, String actor) {
        NotificationDelivery d = deliveries.save(NotificationDelivery.builder().notificationId(n.getId()).channel("feishu").status("pending").retryCount(0).cardPayload(t.feishuCardJson()).build());
        try {
            if (user.getFeishuOpenId() == null || user.getFeishuOpenId().isBlank()) throw new IllegalArgumentException("收件人未绑定飞书 Open ID");
            d.setExternalMessageId(feishu.sendInteractiveMessage(user.getFeishuOpenId(), t.feishuCardJson())); d.setStatus("delivered"); d.setDeliveredAt(LocalDateTime.now());
            deliveries.save(d); audit(event, n, d, "feishu_delivered", actor, "工作流飞书通知已投递");
        } catch (Exception e) {
            String cardError = limit(e.getMessage());
            try {
                d.setExternalMessageId(feishu.sendTextMessage(user.getFeishuOpenId(), t.content()));
                d.setStatus("delivered"); d.setDeliveredAt(LocalDateTime.now());
                d.setErrorMsg("卡片发送失败，已降级为文本：" + cardError);
                deliveries.save(d);
                audit(event, n, d, "feishu_text_fallback_delivered", actor, "卡片解析失败，已降级为文本通知：" + cardError);
            } catch (Exception fallbackError) {
                d.setStatus("failed"); d.setErrorMsg(limit(cardError + "；文本降级也失败：" + fallbackError.getMessage())); deliveries.save(d);
                audit(event, n, d, "feishu_failed", actor, "卡片和文本通知均失败：" + limit(d.getErrorMsg()));
            }
        }
    }
    private boolean enabled(String key) { return configs.findByConfigKey(key).map(SystemConfig::getConfigValue).map("true"::equalsIgnoreCase).orElse(false); }
    private void audit(NotificationEvent e, Notification n, NotificationDelivery d, String action, String actor, String detail) { audits.save(NotificationAuditLog.builder().eventId(e.getId()).notificationId(n.getId()).deliveryId(d.getId()).action(action).operatorUserId(actor).detail(detail).createdAt(LocalDateTime.now()).build()); }
    private String limit(String s) { return s == null ? "未知错误" : s.substring(0, Math.min(s.length(), 1000)); }
}
