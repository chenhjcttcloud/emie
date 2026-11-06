package com.emie.designpm.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verifyNoInteractions;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.NotificationEvent;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.feishu.service.FeishuBaseService;
import com.emie.designpm.notification.repository.NotificationDeliveryRepository;
import com.emie.designpm.notification.repository.NotificationEventRepository;
import com.emie.designpm.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"},
        showSql = false)
@ContextConfiguration(classes = NotificationWorkflowReminderTest.Persistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationWorkflowReminderTest {
    private static final String TASK_LINK = "/?projectId=11&taskId=21";
    private static final String BASE_URL = "https://pm.example.com";

    @Configuration
    @EnableJpaRepositories(basePackageClasses = {NotificationRepository.class, SystemConfigRepository.class})
    @EntityScan(basePackageClasses = NotificationEvent.class)
    @Import({
        NotificationWorkflowService.class,
        NotificationOutboxService.class,
        NotificationTemplateService.class,
        NotificationRecipientRouter.class
    })
    static class Persistence {}

    @Autowired
    NotificationWorkflowService workflow;

    @Autowired
    NotificationEventRepository events;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    NotificationDeliveryRepository deliveries;

    @Autowired
    SystemConfigRepository configs;

    @Autowired
    UserRepository users;

    @MockBean
    FeishuBaseService feishu;

    @Test
    void concurrentScansCommitOneNotificationAndOneRecoverableFeishuCard() throws Exception {
        configure("notification.feishuEnabled", "true");
        configure("notification.publicBaseUrl", BASE_URL);
        String recipient = "due-reminder-owner";
        users.save(User.builder()
                .userId(recipient)
                .name("子任务负责人")
                .role("designer")
                .feishuOpenId("ou_test_only")
                .build());
        String key = "due-reminder-concurrent";
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                remind(recipient, key);
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                remind(recipient, key);
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        remind(recipient, key);

        var event = events.findByIdempotencyKey(key).orElseThrow();
        var inbox = notifications.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(recipient, "unread");
        assertEquals(1, inbox.size());
        var notification = inbox.getFirst();
        assertEquals(event.getId(), notification.getEventId());
        assertEquals(TASK_LINK, notification.getDeepLink());
        assertEquals(
                "delivered",
                deliveries
                        .findByNotificationIdAndChannel(notification.getId(), "in_app")
                        .orElseThrow()
                        .getStatus());
        var delivery = deliveries
                .findByNotificationIdAndChannel(notification.getId(), "feishu")
                .orElseThrow();
        assertEquals("pending", delivery.getStatus());
        assertNotNull(delivery.getNextRetryAt());
        var card = new ObjectMapper().readTree(delivery.getCardPayload());
        assertEquals(
                BASE_URL + TASK_LINK,
                card.path("body")
                        .path("elements")
                        .get(2)
                        .path("behaviors")
                        .get(0)
                        .path("default_url")
                        .asText());
        verifyNoInteractions(feishu);
    }

    @Test
    void missingLinkDefersReminderAndAnUnboundAssigneeIsAuditable() {
        configure("notification.feishuEnabled", "true");
        configure("notification.publicBaseUrl", "");
        String recipient = "due-reminder-unbound";
        String key = "due-reminder-unbound";
        remind(recipient, key);
        assertFalse(events.existsByIdempotencyKey(key));

        configure("notification.publicBaseUrl", BASE_URL);
        remind(recipient, key);
        var inbox = notifications.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(recipient, "unread");
        assertEquals(1, inbox.size());
        var delivery = deliveries
                .findByNotificationIdAndChannel(inbox.getFirst().getId(), "feishu")
                .orElseThrow();
        assertEquals("blocked", delivery.getStatus());
        verifyNoInteractions(feishu);
    }

    private void configure(String key, String value) {
        SystemConfig config = configs.findByConfigKey(key).orElseGet(() -> SystemConfig.builder()
                .configKey(key)
                .configGroup("notification")
                .build());
        config.setConfigValue(value);
        configs.save(config);
    }

    private void remind(String recipient, String key) {
        workflow.notifyUserOnce(
                "TASK_DUE_SOON",
                recipient,
                "sub_task",
                21L,
                "system",
                key,
                Map.of("projectName", "示例项目", "taskName", "设计", "deadline", "2026-11-06", "taskLink", TASK_LINK));
    }
}
