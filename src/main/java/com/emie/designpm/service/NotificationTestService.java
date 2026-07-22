package com.emie.designpm.service;

import com.emie.designpm.entity.Notification;
import com.emie.designpm.entity.NotificationAuditLog;
import com.emie.designpm.entity.NotificationDelivery;
import com.emie.designpm.entity.NotificationEvent;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.NotificationAuditLogRepository;
import com.emie.designpm.repository.NotificationDeliveryRepository;
import com.emie.designpm.repository.NotificationRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/** 管理员用于验证站内与飞书通知配置的真实投递闭环。 */
@Service
public class NotificationTestService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationOutboxService outboxService;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final SystemConfigRepository configRepository;
    private final FeishuBaseService feishuBaseService;

    public NotificationTestService(NotificationOutboxService outboxService,
                                   NotificationRepository notificationRepository,
                                   NotificationDeliveryRepository deliveryRepository,
                                   NotificationAuditLogRepository auditLogRepository,
                                   UserRepository userRepository,
                                   SystemConfigRepository configRepository,
                                   FeishuBaseService feishuBaseService) {
        this.outboxService = outboxService;
        this.notificationRepository = notificationRepository;
        this.deliveryRepository = deliveryRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.configRepository = configRepository;
        this.feishuBaseService = feishuBaseService;
    }

    @Transactional
    public Map<String, Object> sendTest(String userId) {
        User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("当前登录用户不存在"));
        NotificationEvent event = outboxService.publish(
                "NOTIFICATION_CHANNEL_TEST", "notification_test", user.getId(), 1, userId,
                "notification-test-" + UUID.randomUUID(), "管理员发起通知渠道测试");
        Notification notification = notificationRepository.save(Notification.builder()
                .eventId(event.getId()).recipientUserId(userId).category("system")
                .priority("normal").mandatory(false).title("通知渠道测试")
                .content("这是一条由系统设置发起的测试通知。若你同时收到了飞书卡片，说明飞书通知已配置成功。")
                .deepLink("/admin?tab=config").aggregateType("notification_test")
                .aggregateId(user.getId()).status("unread").createdAt(LocalDateTime.now()).build());

        NotificationDelivery inApp = deliveryRepository.save(NotificationDelivery.builder()
                .notificationId(notification.getId()).channel("in_app").status("delivered")
                .retryCount(0).deliveredAt(LocalDateTime.now()).build());
        audit(notification, inApp, "test_in_app_delivered", userId, "站内测试通知已创建");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("notificationId", notification.getId());
        result.put("inApp", "delivered");

        if (!isEnabled("notification.feishuEnabled")) {
            result.put("feishu", "disabled");
            result.put("message", "站内测试通知已发送；飞书通知当前未启用");
            return result;
        }
        if (user.getFeishuOpenId() == null || user.getFeishuOpenId().isBlank()) {
            NotificationDelivery delivery = deliveryRepository.save(NotificationDelivery.builder()
                    .notificationId(notification.getId()).channel("feishu").status("blocked")
                    .retryCount(0).errorMsg("当前用户未绑定飞书 Open ID").build());
            audit(notification, delivery, "test_feishu_blocked", userId, "当前用户未绑定飞书 Open ID");
            result.put("feishu", "unbound");
            result.put("message", "站内测试通知已发送；当前账号未绑定飞书 Open ID");
            return result;
        }

        NotificationDelivery delivery = deliveryRepository.save(NotificationDelivery.builder()
                .notificationId(notification.getId()).channel("feishu").status("pending").retryCount(0).build());
        try {
            String messageId = feishuBaseService.sendInteractiveMessage(user.getFeishuOpenId(), buildCard());
            delivery.setStatus("delivered");
            delivery.setExternalMessageId(messageId);
            delivery.setDeliveredAt(LocalDateTime.now());
            deliveryRepository.save(delivery);
            audit(notification, delivery, "test_feishu_delivered", userId, "飞书测试消息已投递");
            result.put("feishu", "delivered");
            result.put("message", "站内和飞书测试通知均已发送");
        } catch (Exception e) {
            delivery.setStatus("failed");
            delivery.setErrorMsg(limitError(e.getMessage()));
            deliveryRepository.save(delivery);
            audit(notification, delivery, "test_feishu_failed", userId, "飞书测试失败：" + limitError(e.getMessage()));
            result.put("feishu", "failed");
            result.put("message", "站内测试通知已发送；飞书发送失败：" + limitError(e.getMessage()));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> sendTemporaryBroadcast(String title, String content, String operatorUserId) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("通知标题和内容不能为空");
        }
        if (!isEnabled("notification.feishuEnabled")) {
            throw new IllegalArgumentException("飞书通知当前未启用");
        }
        int total = 0, delivered = 0, failed = 0, unbound = 0;
        for (User user : userRepository.findAll()) {
            if (user.getStatus() != null && "disabled".equalsIgnoreCase(user.getStatus())) continue;
            total++;
            NotificationEvent event = outboxService.publish("TEMPORARY_FEISHU_BROADCAST", "temporary_broadcast",
                    user.getId(), 1, operatorUserId, "temporary-broadcast-" + UUID.randomUUID(), title);
            Notification notification = notificationRepository.save(Notification.builder()
                    .eventId(event.getId()).recipientUserId(user.getUserId()).category("system")
                    .priority("high").mandatory(false).title(title).content(content)
                    .deepLink("/").aggregateType("temporary_broadcast").aggregateId(user.getId())
                    .status("unread").createdAt(LocalDateTime.now()).build());
            if (user.getFeishuOpenId() == null || user.getFeishuOpenId().isBlank()) {
                unbound++;
                NotificationDelivery d = deliveryRepository.save(NotificationDelivery.builder().notificationId(notification.getId())
                        .channel("feishu").status("blocked").retryCount(0).errorMsg("未绑定飞书 Open ID").build());
                audit(notification, d, "temporary_broadcast_unbound", operatorUserId, "未绑定飞书 Open ID");
                continue;
            }
            try {
                String messageId = feishuBaseService.sendInteractiveMessage(user.getFeishuOpenId(), buildBroadcastCard(title, content));
                delivered++;
                NotificationDelivery d = deliveryRepository.save(NotificationDelivery.builder().notificationId(notification.getId())
                        .channel("feishu").status("delivered").retryCount(0).externalMessageId(messageId)
                        .deliveredAt(LocalDateTime.now()).build());
                audit(notification, d, "temporary_broadcast_delivered", operatorUserId, "临时飞书通知已发送");
            } catch (Exception e) {
                failed++;
                NotificationDelivery d = deliveryRepository.save(NotificationDelivery.builder().notificationId(notification.getId())
                        .channel("feishu").status("failed").retryCount(0).errorMsg(limitError(e.getMessage())).build());
                audit(notification, d, "temporary_broadcast_failed", operatorUserId, "临时飞书通知失败：" + limitError(e.getMessage()));
            }
        }
        return Map.of("total", total, "delivered", delivered, "failed", failed, "unbound", unbound);
    }

    private String buildBroadcastCard(String title, String content) throws Exception {
        ObjectNode card = JSON.createObjectNode();
        card.put("schema", "2.0");
        ObjectNode header = card.putObject("header"); header.put("template", "blue");
        header.putObject("title").put("tag", "plain_text").put("content", title);
        card.putObject("body").putArray("elements").addObject().put("tag", "markdown").put("content", content);
        return JSON.writeValueAsString(card);
    }

    private boolean isEnabled(String key) {
        return configRepository.findByConfigKey(key).map(SystemConfig::getConfigValue)
                .map("true"::equalsIgnoreCase).orElse(false);
    }

    static String buildCard() throws Exception {
        ObjectNode card = JSON.createObjectNode();
        card.put("schema", "2.0");
        ObjectNode header = card.putObject("header");
        header.put("template", "blue");
        header.putObject("title").put("tag", "plain_text").put("content", "EMIE 通知渠道测试");
        card.putObject("body").putArray("elements").addObject().put("tag", "markdown")
                .put("content", "站内通知与飞书通知配置正在验证。若你看到此卡片，飞书通知渠道已可用。");
        return JSON.writeValueAsString(card);
    }

    private void audit(Notification notification, NotificationDelivery delivery, String action, String userId, String detail) {
        auditLogRepository.save(NotificationAuditLog.builder().eventId(notification.getEventId())
                .notificationId(notification.getId()).deliveryId(delivery.getId()).action(action)
                .operatorUserId(userId).detail(detail).createdAt(LocalDateTime.now()).build());
    }

    private String limitError(String message) {
        if (message == null || message.isBlank()) return "未知错误";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
