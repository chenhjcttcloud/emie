package com.emie.designpm.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.auth.AuthSessions;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FeishuSyncPermissionFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/stats", "/config", "/v2/status", "/schema-diagnostics"})
    void syncViewerCanReadWithoutExecutionPermission(String endpoint) throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("admin", "admin.feishu_sync.view")).thenReturn(true);

        var result = filter(permissions, "GET", endpoint);

        assertEquals(200, result.response().getStatus());
        assertTrue(result.reachedController());
        verify(permissions).has("admin", "admin.feishu_sync.view");
        assertEquals("admin.feishu_sync.view", result.request().getAttribute("requiredPermission"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/stats", "/config", "/v2/status", "/schema-diagnostics"})
    void deniedSyncViewerCannotReadEvenWithExecutionPermission(String endpoint) throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("admin", "admin.feishu_sync.execute")).thenReturn(true);

        var result = filter(permissions, "GET", endpoint);

        assertEquals(403, result.response().getStatus());
        assertFalse(result.reachedController());
        assertTrue(result.response().getContentAsString().contains("admin.feishu_sync.view"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/process",
                "/queue/19/retry",
                "/v2/prepare",
                "/v2/activate",
                "/v2/rollback",
                "/full-resync",
                "/validate-backups"
            })
    void viewingSyncDoesNotPermitExecutingOperations(String endpoint) throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("admin", "admin.feishu_sync.view")).thenReturn(true);

        var result = filter(permissions, "POST", endpoint);

        assertEquals(403, result.response().getStatus());
        assertFalse(result.reachedController());
        verify(permissions).has("admin", "admin.feishu_sync.execute");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/process", "/queue/19/retry", "/v2/prepare", "/v2/activate", "/v2/rollback", "/full-resync"})
    void explicitlyGrantedExecutionCanReachTheController(String endpoint) throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("admin", "admin.feishu_sync.execute")).thenReturn(true);

        var result = filter(permissions, "POST", endpoint);

        assertEquals(200, result.response().getStatus());
        assertTrue(result.reachedController());
        assertEquals("admin.feishu_sync.execute", result.request().getAttribute("requiredPermission"));
    }

    private FilterResult filter(PermissionService permissions, String method, String endpoint) throws Exception {
        String token = AuthSessions.generateToken();
        AuthSessions.put(token, new AuthSession("sync-permission-fixture", "admin", "同步权限测试"));
        try {
            var request = new MockHttpServletRequest(method, "/api/admin/sync" + endpoint);
            request.addHeader("X-Auth-Token", token);
            var response = new MockHttpServletResponse();
            AtomicBoolean reachedController = new AtomicBoolean();
            new AuthFilter(permissions).doFilter(request, response, (req, res) -> reachedController.set(true));
            return new FilterResult(request, response, reachedController.get());
        } finally {
            AuthSessions.remove(token);
        }
    }

    private record FilterResult(
            MockHttpServletRequest request, MockHttpServletResponse response, boolean reachedController) {}
}
