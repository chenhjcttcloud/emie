package com.emie.designpm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.entity.SystemConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationTemplateServiceTest {
    private final SystemConfigRepository configs = mock(SystemConfigRepository.class);
    private final NotificationTemplateService service = new NotificationTemplateService(configs);

    @Test
    void redeliveryTemplateKeepsDeliveryCountAndRejectionReason() throws Exception {
        var result = service.render("TASK_REDELIVERED", Map.of("projectName", "春季水杯", "taskName", "包装设计", "actorName", "小李", "deliveryCount", "2", "reason", "请补充背面图", "taskLink", "https://example.com/task/1"));
        assertTrue(result.content().contains("第2次交付"));
        assertTrue(result.content().contains("请补充背面图"));
        assertTrue(new ObjectMapper().readTree(result.feishuCardJson()).path("body").path("elements").isArray());
    }

    @Test
    void mandatoryWorkflowEventsHaveConcreteTemplates() {
        for (String event : new String[]{"PROJECT_ASSIGNED", "MATERIAL_MARKET_PLANNER_PENDING", "TASK_ASSIGNED", "TASK_DELIVERED", "TASK_REJECTED", "TASK_REDELIVERED", "REVIEW_PENDING", "PROJECT_REMINDER", "TASK_OVERDUE",
                "DESIGN_REQUIREMENT_ASSIGNED", "DESIGN_REQUIREMENT_DESIGNER_ASSIGNED",
                "DESIGN_REQUIREMENT_DELIVERED", "DESIGN_REQUIREMENT_REVIEW_PENDING", "DESIGN_REQUIREMENT_REJECTED",
                "DESIGN_REQUIREMENT_TERMINATED"}) {
            var result = service.render(event, Map.of());
            assertTrue(result.mandatory(), event + " 必须为必达通知");
            assertFalse(result.feishuCardJson().isBlank());
        }
    }

    @Test
    void materialMarketPlannerPendingUsesDedicatedCopy() {
        var result = service.render("MATERIAL_MARKET_PLANNER_PENDING", Map.of(
                "projectName", "桌面收纳灯", "actorName", "销售小李", "projectLink", "/?projectId=109"));
        assertTrue(result.title().contains("素材广场"));
        assertTrue(result.content().contains("桌面收纳灯"));
        assertTrue(result.content().contains("销售小李"));
    }

    @Test
    void legacyProjectDeepLinkIsConvertedToSpaProjectLink() {
        var result = service.render("MATERIAL_MARKET_PLANNER_PENDING", Map.of(
                "projectName", "项目 A", "projectLink", "/projects/114"));
        assertEquals("/?projectId=114", result.deepLink());
    }

    @Test
    void configuredTemplateOverridesDefaultAndResolvesVariables() {
        when(configs.findByConfigKey("notification.template.TASK_ASSIGNED.title"))
                .thenReturn(Optional.of(SystemConfig.builder().configValue("请处理：{{taskName}}").build()));
        when(configs.findByConfigKey("notification.template.TASK_ASSIGNED.content"))
                .thenReturn(Optional.of(SystemConfig.builder().configValue("{{actorName}}派发了{{projectName}}，截止{{deadline}}").build()));
        var result = service.render("TASK_ASSIGNED", Map.of("projectName", "环球杯子", "taskName", "小黄人包装", "actorName", "小李", "deadline", "2026-07-20"));
        assertEquals("请处理：小黄人包装", result.title());
        assertEquals("小李派发了环球杯子，截止2026-07-20", result.content());
    }
}
