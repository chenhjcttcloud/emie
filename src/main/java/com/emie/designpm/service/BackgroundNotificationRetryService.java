package com.emie.designpm.service;

import com.emie.designpm.background.repository.NotificationAuditLogRepository;
import com.emie.designpm.background.repository.NotificationDeliveryRepository;
import com.emie.designpm.background.repository.NotificationEventRepository;
import com.emie.designpm.background.repository.NotificationRepository;
import com.emie.designpm.background.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 后台连接池通知重试实现；后续在隔离开关启用时接管定时投递。 */
@Service
@ConditionalOnProperty(name = "app.db.pool.isolation.enabled", havingValue = "true")
public class BackgroundNotificationRetryService {
    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final NotificationAuditLogRepository audits;
    private final NotificationEventRepository events;

    public BackgroundNotificationRetryService(NotificationDeliveryRepository deliveries,
            NotificationRepository notifications, UserRepository users,
            NotificationAuditLogRepository audits, NotificationEventRepository events) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.users = users;
        this.audits = audits;
        this.events = events;
    }
}
