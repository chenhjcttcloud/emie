package com.emie.designpm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.emie.designpm.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationTemplateServiceTest {
    private final NotificationTemplateService service = new NotificationTemplateService(mock(SystemConfigRepository.class));

    @Test
    void redeliveryTemplateKeepsDeliveryCountAndRejectionReason() throws Exception {
        var result = service.render("TASK_REDELIVERED", Map.of("projectName", "春季水杯", "taskName", "包装设计", "actorName", "小李", "deliveryCount", "2", "reason", "请补充背面图", "taskLink", "https://example.com/task/1"));
        assertTrue(result.content().contains("第2次交付"));
        assertTrue(result.content().contains("请补充背面图"));
        assertTrue(new ObjectMapper().readTree(result.feishuCardJson()).path("body").path("elements").isArray());
    }

    @Test
    void mandatoryWorkflowEventsHaveConcreteTemplates() {
        for (String event : new String[]{"PROJECT_ASSIGNED", "TASK_ASSIGNED", "TASK_DELIVERED", "TASK_REJECTED", "TASK_REDELIVERED", "REVIEW_PENDING", "PROJECT_REMINDER", "TASK_OVERDUE"}) {
            var result = service.render(event, Map.of());
            assertTrue(result.mandatory(), event + " 必须为必达通知");
            assertFalse(result.feishuCardJson().isBlank());
        }
    }
}
