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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void temporaryBroadcastAcceptsLegacyFeishuSwitchWhenDedicatedSwitchIsNotInitialized() {
        Dependencies dependencies = new Dependencies();
        SystemConfig enabled = new SystemConfig();
        enabled.setConfigValue("true");
        when(dependencies.configs.findByConfigKey("notification.feishuEnabled")).thenReturn(Optional.empty());
        when(dependencies.configs.findByConfigKey("feishu.enabled")).thenReturn(Optional.of(enabled));

        assertDoesNotThrow(() -> dependencies.service.validateTemporaryBroadcast("标题", "正文"));
    }

    @Test
    void temporaryBroadcastRoutesToOnlyTheConfiguredTesterInTestMode() throws Exception {
        Dependencies dependencies = new Dependencies();
        SystemConfig enabled = new SystemConfig();
        enabled.setConfigValue("true");
        var tester = com.emie.designpm.entity.User.builder().id(7L).userId("tester_01").name("测试账号")
                .role("admin").status("active").feishuOpenId("open_tester").build();
        var planner = com.emie.designpm.entity.User.builder().id(8L).userId("planner_01").name("产品企划")
                .role("planner").status("active").feishuOpenId("open_planner").build();
        when(dependencies.configs.findByConfigKey("notification.feishuEnabled")).thenReturn(Optional.of(enabled));
        when(dependencies.users.findAll()).thenReturn(List.of(tester, planner));
        when(dependencies.router.routeAll(List.of("tester_01", "planner_01"))).thenReturn(List.of("tester_01"));
        when(dependencies.outbox.publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.emie.designpm.entity.NotificationEvent.builder().id(1L).build());
        when(dependencies.notifications.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var notification = call.getArgument(0, com.emie.designpm.entity.Notification.class);
            notification.setId(1L);
            return notification;
        });
        when(dependencies.deliveries.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var delivery = call.getArgument(0, com.emie.designpm.entity.NotificationDelivery.class);
            delivery.setId(1L);
            return delivery;
        });

        var result = dependencies.service.sendTemporaryBroadcast("测试标题", "测试正文", "admin_01");

        assertEquals(1, result.get("total"));
        verify(dependencies.feishu).sendInteractiveMessage(org.mockito.ArgumentMatchers.eq("open_tester"), org.mockito.ArgumentMatchers.anyString());
    }

    private static class Dependencies {
        private final NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        private final NotificationRepository notifications = mock(NotificationRepository.class);
        private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        private final NotificationAuditLogRepository auditLogs = mock(NotificationAuditLogRepository.class);
        private final UserRepository users = mock(UserRepository.class);
        private final SystemConfigRepository configs = mock(SystemConfigRepository.class);
        private final FeishuBaseService feishu = mock(FeishuBaseService.class);
        private final NotificationRecipientRouter router = mock(NotificationRecipientRouter.class);
        private final NotificationTestService service = new NotificationTestService(outbox, notifications,
                deliveries, auditLogs, users, configs, feishu, router);
    }
}
