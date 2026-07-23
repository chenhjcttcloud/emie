package com.emie.designpm.service;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.NotificationAuditLogRepository;
import com.emie.designpm.repository.NotificationDeliveryRepository;
import com.emie.designpm.repository.NotificationRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationTestServiceTest {

    @Test
    void testCardUsesFeishuCardJsonV2BodyObject() throws Exception {
        JsonNode card = new ObjectMapper().readTree(NotificationTestService.buildCard());

        assertEquals("2.0", card.path("schema").asText());
        assertTrue(card.path("body").isObject(), "JSON 2.0 的 body 必须是对象");
        assertTrue(card.path("body").path("elements").isArray(), "body 中必须提供 elements 数组");
        assertEquals("markdown", card.path("body").path("elements").get(0).path("tag").asText());
    }

    @Test
    void temporaryBroadcastRejectsBlankContentBeforeCreatingRecords() {
        Dependencies dependencies = new Dependencies();

        assertThrows(IllegalArgumentException.class,
                () -> dependencies.service.sendTemporaryBroadcast("标题", "  ", "admin-1"));

        verifyNoInteractions(dependencies.users, dependencies.notifications, dependencies.deliveries,
                dependencies.auditLogs, dependencies.outbox, dependencies.feishu);
    }

    @Test
    void temporaryBroadcastRequiresEnabledFeishuChannel() {
        Dependencies dependencies = new Dependencies();
        SystemConfig disabled = new SystemConfig();
        disabled.setConfigValue("false");
        when(dependencies.configs.findByConfigKey("notification.feishuEnabled"))
                .thenReturn(Optional.of(disabled));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dependencies.service.sendTemporaryBroadcast("标题", "正文", "admin-1"));

        assertEquals("飞书通知当前未启用", error.getMessage());
        verifyNoInteractions(dependencies.users, dependencies.notifications, dependencies.deliveries,
                dependencies.auditLogs, dependencies.outbox, dependencies.feishu);
    }

    private static class Dependencies {
        private final NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        private final NotificationRepository notifications = mock(NotificationRepository.class);
        private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        private final NotificationAuditLogRepository auditLogs = mock(NotificationAuditLogRepository.class);
        private final UserRepository users = mock(UserRepository.class);
        private final SystemConfigRepository configs = mock(SystemConfigRepository.class);
        private final FeishuBaseService feishu = mock(FeishuBaseService.class);
        private final NotificationTestService service = new NotificationTestService(outbox, notifications,
                deliveries, auditLogs, users, configs, feishu);
    }
}
