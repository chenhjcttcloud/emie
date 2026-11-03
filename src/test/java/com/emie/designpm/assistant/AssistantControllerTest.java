package com.emie.designpm.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.assistant.controller.AssistantController;
import com.emie.designpm.auth.AuthSession;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AssistantControllerTest {
    @Test
    void settingsRequireAdminAndExactConfigPermission() {
        var assistant = mock(AssistantService.class);
        var settings = mock(AssistantModelSettings.class);
        var permissions = mock(PermissionService.class);
        var controller = new AssistantController(assistant, settings, permissions, mock(AssistantModelDiscovery.class));
        var request = new MockHttpServletRequest();
        when(permissions.has(anyString(), anyString())).thenReturn(true);
        request.setAttribute("authSession", new AuthSession("designer-one", "designer", "设计师"));
        assertEquals(403, controller.config(request).getStatusCode().value());
        assertEquals(
                403, controller.saveConfig(Map.of(), request).getStatusCode().value());
        verifyNoInteractions(settings);
        request.setAttribute("authSession", new AuthSession("admin-one", "admin", "管理员"));
        when(permissions.has("admin", "admin.config.view")).thenReturn(false);
        assertEquals(403, controller.config(request).getStatusCode().value());
        when(permissions.has("admin", "admin.config.view")).thenReturn(true);
        when(settings.overview()).thenReturn(Map.of("keyConfigured", true, "enabled", true));
        assertEquals(200, controller.config(request).getStatusCode().value());
        assertFalse(((Map<?, ?>) controller.config(request).getBody()).containsKey("apiKey"));
    }

    @Test
    void chatRequiresAuthenticatedSession() {
        var controller = new AssistantController(
                mock(AssistantService.class),
                mock(AssistantModelSettings.class),
                mock(PermissionService.class),
                mock(AssistantModelDiscovery.class));
        assertEquals(
                401,
                controller
                        .chat(new AssistantRequest("你好", null, null, null), new MockHttpServletRequest())
                        .getStatusCode()
                        .value());
    }

    @Test
    void utf8HistoryWithinTheDtoLimitFitsTheHttpBodyLimit() {
        var assistant = mock(AssistantService.class);
        var controller = new AssistantController(
                assistant,
                mock(AssistantModelSettings.class),
                mock(PermissionService.class),
                mock(AssistantModelDiscovery.class));
        var request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("designer-one", "designer", "设计师"));
        request.setContent("中".repeat(18000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(assistant.chat(any(), any())).thenReturn(Map.of("mode", "guide"));
        assertEquals(
                200,
                controller
                        .chat(new AssistantRequest("你好", null, null, null), request)
                        .getStatusCode()
                        .value());
        request.setContent(new byte[65537]);
        assertEquals(
                413,
                controller
                        .chat(new AssistantRequest("你好", null, null, null), request)
                        .getStatusCode()
                        .value());
    }

    @Test
    void ordinaryEmployeesCanReadOnlyPublicActiveModelMetadata() {
        var settings = mock(AssistantModelSettings.class);
        var controller = new AssistantController(
                mock(AssistantService.class),
                settings,
                mock(PermissionService.class),
                mock(AssistantModelDiscovery.class));
        var request = new MockHttpServletRequest();
        assertEquals(401, controller.model(request).getStatusCode().value());
        request.setAttribute("authSession", new AuthSession("designer-one", "designer", "设计师"));
        when(settings.publicModel())
                .thenReturn(Map.of(
                        "modelName",
                        "服务器 Kimi",
                        "modelId",
                        "server",
                        "provider",
                        "kimi",
                        "visionSupported",
                        true,
                        "enabled",
                        true));
        var response = controller.model(request);
        assertEquals(200, response.getStatusCode().value());
        assertFalse(((Map<?, ?>) response.getBody()).containsKey("baseUrl"));
        assertFalse(((Map<?, ?>) response.getBody()).containsKey("apiKey"));
        assertEquals(403, controller.config(request).getStatusCode().value());
    }

    @Test
    void discoveryRequiresAdminEditPermissionAndNeverUsesSavedConfiguration() throws Exception {
        var settings = mock(AssistantModelSettings.class);
        var discovery = mock(AssistantModelDiscovery.class);
        var permissions = mock(PermissionService.class);
        var controller = new AssistantController(mock(AssistantService.class), settings, permissions, discovery);
        var request = new MockHttpServletRequest();
        var body = Map.<String, Object>of("baseUrl", "https://example.com/v1", "apiKey", "fixture-secret");
        request.setAttribute("authSession", new AuthSession("designer", "designer", "员工"));
        assertEquals(
                403, controller.discoverModels(body, request).getStatusCode().value());
        request.setAttribute("authSession", new AuthSession("admin", "admin", "管理员"));
        when(permissions.has("admin", "admin.config.view")).thenReturn(true);
        assertEquals(
                403, controller.discoverModels(body, request).getStatusCode().value());
        verifyNoInteractions(discovery, settings);
        when(permissions.has("admin", "admin.config.edit")).thenReturn(true);
        when(discovery.discover(body)).thenReturn(Map.of("models", java.util.List.of(Map.of("id", "fixture-model"))));
        var result = controller.discoverModels(body, request);
        assertEquals(200, result.getStatusCode().value());
        assertFalse(result.getBody().toString().contains("fixture-secret"));
        verifyNoInteractions(settings);
        request.setContent(new byte[16385]);
        assertEquals(
                413, controller.discoverModels(body, request).getStatusCode().value());
        verify(discovery, times(1)).discover(body);
    }

    @Test
    void discoveryReturnsSafeServiceErrorsWithoutCredentials() throws Exception {
        var discovery = mock(AssistantModelDiscovery.class);
        var permissions = mock(PermissionService.class);
        var controller = new AssistantController(
                mock(AssistantService.class), mock(AssistantModelSettings.class), permissions, discovery);
        var request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("admin", "admin", "管理员"));
        when(permissions.has("admin", "admin.config.edit")).thenReturn(true);
        when(discovery.discover(anyMap())).thenThrow(new AssistantModelDiscovery.DiscoveryFailure(504, "获取模型超时，请稍后重试"));
        assertEquals(
                504,
                controller.discoverModels(Map.of(), request).getStatusCode().value());
    }
}
