package com.emie.designpm.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.auth.AuthSessions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class VisualAiPermissionFilterTest {
    @Test
    void readingAiConfigurationUsesConfigurationViewPermission() throws Exception {
        check("GET", "/api/admin/visual-ai-config", "admin.config.view");
    }

    @Test
    void changingAiConfigurationRequiresConfigurationEditPermission() throws Exception {
        check("POST", "/api/admin/visual-ai-config", "admin.config.edit");
    }

    @Test
    void startingPaidLibraryAnalysisRequiresConfigurationEditPermission() throws Exception {
        check("POST", "/api/admin/visual-ai-config/index-library", "admin.config.edit");
    }

    private void check(String method, String path, String permission) throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("planner", "page.admin.view")).thenReturn(true);
        String token = AuthSessions.generateToken();
        AuthSessions.put(token, new AuthSession("ai-permission-fixture", "planner", "本地权限测试"));
        try {
            var request = new MockHttpServletRequest(method, path);
            request.addHeader("X-Auth-Token", token);
            var response = new MockHttpServletResponse();
            new AuthFilter(permissions).doFilter(request, response, (req, res) -> {
                throw new AssertionError("缺少配置权限时不可进入控制器");
            });
            assertEquals(403, response.getStatus());
            verify(permissions).has("planner", permission);
        } finally {
            AuthSessions.remove(token);
        }
    }
}
