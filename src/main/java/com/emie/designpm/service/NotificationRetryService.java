package com.emie.designpm.service;

import com.emie.designpm.entity.Notification;
import com.emie.designpm.entity.NotificationAuditLog;
import com.emie.designpm.entity.NotificationDelivery;
import com.emie.designpm.entity.User;
import com.emie.designpm.entity.NotificationEvent;
import com.emie.designpm.repository.NotificationAuditLogRepository;
import com.emie.designpm.repository.NotificationDeliveryRepository;
import com.emie.designpm.repository.NotificationRepository;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.repository.NotificationEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 失败的飞书投递按退避时间重试，超过上限进入死信并保留审计。 */
@Service
public class NotificationRetryService {
    private static final int MAX_RETRIES = 5;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final NotificationAuditLogRepository audits;
    private final FeishuBaseService feishu;
    private final NotificationEventRepository events;

    public NotificationRetryService(NotificationDeliveryRepository deliveries, NotificationRepository notifications,
                                    UserRepository users, NotificationAuditLogRepository audits, FeishuBaseService feishu) {
        this(deliveries, notifications, users, audits, feishu, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationRetryService(NotificationDeliveryRepository deliveries, NotificationRepository notifications,
                                    UserRepository users, NotificationAuditLogRepository audits, FeishuBaseService feishu,
                                    NotificationEventRepository events) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.users = users;
        this.audits = audits;
        this.feishu = feishu;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${notification.retry-delay-ms:30000}")
    @Transactional
    public void retryDueDeliveries() {
        List<NotificationDelivery> due = deliveries
                .findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        List.of("failed", "pending"), LocalDateTime.now());
        Map<Long, Notification> notificationById = notifications.findByIdIn(due.stream()
                .map(NotificationDelivery::getNotificationId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Notification::getId, n -> n));
        Map<String, User> userById = users.findByUserIdIn(notificationById.values().stream()
                .map(Notification::getRecipientUserId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(User::getUserId, u -> u));
        for (NotificationDelivery delivery : due) retry(delivery, notificationById, userById);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentFailures() {
        return recentFeishuDeliveries();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentFeishuDeliveries() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        List<NotificationDelivery> recent = deliveries.findRecentFeishu();
        Map<Long, Notification> byId = notifications.findByIdIn(recent.stream()
                .map(NotificationDelivery::getNotificationId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Notification::getId, n -> n));
        for (NotificationDelivery d : recent) {
            Notification n = byId.get(d.getNotificationId());
            if (n != null) {
                Map<String, Object> row = new HashMap<>();
                row.put("deliveryId", d.getId());
                row.put("notificationId", n.getId());
                row.put("recipientUserId", n.getRecipientUserId());
                users.findByUserId(n.getRecipientUserId()).ifPresent(u -> row.put("recipientName", u.getName()));
                NotificationEvent event = events == null ? null : events.findById(n.getEventId()).orElse(null);
                if (event != null) {
                    row.put("eventType", event.getEventType());
                    row.put("processLabel", processLabel(event.getEventType()));
                    row.put("actorUserId", event.getActorUserId());
                }
                row.put("aggregateType", n.getAggregateType());
                row.put("aggregateId", n.getAggregateId());
                row.put("title", n.getTitle());
                row.put("content", n.getContent());
                row.put("status", d.getStatus());
                row.put("statusLabel", statusLabel(d.getStatus()));
                // 触发时间属于业务通知事件，而不是投递队列记录。历史数据在新增
                // notification_deliveries.created_at 时可能被填成迁移时间，不能作为触发时间。
                row.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt() : d.getCreatedAt());
                row.put("firstAttemptAt", d.getFirstAttemptAt());
                row.put("lastAttemptAt", d.getLastAttemptAt());
                row.put("failedAt", d.getFailedAt());
                row.put("nextRetryAt", d.getNextRetryAt());
                row.put("retryCount", d.getRetryCount());
                row.put("errorMsg", d.getErrorMsg());
                row.put("nextRetryAt", d.getNextRetryAt());
                row.put("deliveredAt", d.getDeliveredAt());
                result.add(row);
            }
        }
        return result.stream().limit(100).toList();
    }

    private String processLabel(String eventType) {
        return switch (eventType) {
            case "TASK_ASSIGNED" -> "产品企划派发子任务";
            case "TASK_DELIVERED" -> "子任务负责人首次交付成果";
            case "TASK_REJECTED" -> "产品企划驳回子任务";
            case "TASK_REDELIVERED" -> "子任务负责人重新交付成果";
            case "TASK_SUBMITTED_FOR_REVIEW" -> "子任务送审";
            case "REVIEW_APPROVED" -> "子任务验收通过";
            default -> eventType == null ? "其他系统通知" : eventType;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "delivered" -> "发送成功";
            case "pending" -> "等待发送";
            case "failed" -> "发送失败/等待重试";
            case "dead_letter" -> "失败（已停止重试）";
            case "blocked" -> "未发送（未绑定飞书）";
            default -> status == null ? "未知" : status;
        };
    }

    @Transactional
    public void retryNow(Long deliveryId, String operatorUserId) {
        NotificationDelivery d = deliveries.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("通知投递记录不存在"));
        if (!"feishu".equals(d.getChannel())) throw new IllegalArgumentException("仅支持重试飞书通知");
        d.setStatus("failed");
        d.setRetryCount(0);
        d.setLastAttemptAt(LocalDateTime.now());
        d.setNextRetryAt(LocalDateTime.now());
        d.setErrorMsg("管理员手动重新排队");
        deliveries.save(d);
        notifications.findById(d.getNotificationId()).ifPresent(n -> audit(n, d, "admin_retry_queued", "管理员 " + operatorUserId + " 手动重新排队"));
    }

    private void retry(NotificationDelivery delivery, Map<Long, Notification> notificationById, Map<String, User> userById) {
        Notification notification = notificationById.get(delivery.getNotificationId());
        if (notification == null || delivery.getCardPayload() == null || delivery.getCardPayload().isBlank()) {
            deadLetter(delivery, "通知内容或卡片载荷不存在");
            return;
        }
        User user = userById.get(notification.getRecipientUserId());
        if (user == null || user.getFeishuOpenId() == null || user.getFeishuOpenId().isBlank()) {
            scheduleOrDeadLetter(delivery, "收件人未绑定飞书 Open ID");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            if (delivery.getFirstAttemptAt() == null) delivery.setFirstAttemptAt(now);
            delivery.setLastAttemptAt(now);
            delivery.setExternalMessageId(feishu.sendInteractiveMessage(user.getFeishuOpenId(), delivery.getCardPayload()));
            delivery.setStatus("delivered");
            delivery.setFailedAt(null);
            delivery.setDeliveredAt(LocalDateTime.now());
            delivery.setNextRetryAt(null);
            deliveries.save(delivery);
            audit(notification, delivery, "feishu_retry_delivered", "重试成功");
        } catch (Exception e) {
            scheduleOrDeadLetter(delivery, limit(e.getMessage()));
        }
    }

    private void scheduleOrDeadLetter(NotificationDelivery delivery, String error) {
        delivery.setLastAttemptAt(LocalDateTime.now());
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setRetryCount((delivery.getRetryCount() == null ? 0 : delivery.getRetryCount()) + 1);
        delivery.setErrorMsg(error);
        if (delivery.getRetryCount() >= MAX_RETRIES) {
            deadLetter(delivery, error);
            return;
        }
        delivery.setStatus("failed");
        delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(Math.min(900, 30L << Math.min(delivery.getRetryCount(), 4))));
        deliveries.save(delivery);
    }

    private void deadLetter(NotificationDelivery delivery, String error) {
        delivery.setStatus("dead_letter");
        delivery.setErrorMsg(error);
        delivery.setNextRetryAt(null);
        deliveries.save(delivery);
        notifications.findById(delivery.getNotificationId()).ifPresent(n -> audit(n, delivery, "feishu_dead_letter", error));
    }

    private void audit(Notification n, NotificationDelivery d, String action, String detail) {
        audits.save(NotificationAuditLog.builder().eventId(n.getEventId()).notificationId(n.getId())
                .deliveryId(d.getId()).action(action).detail(detail).createdAt(LocalDateTime.now()).build());
    }

    private String limit(String value) { return value == null ? "未知错误" : value.substring(0, Math.min(1000, value.length())); }
}
