package com.emie.designpm.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSessions;
import com.emie.designpm.entity.User;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthControllerImpersonationTest {
    private static final String ADMIN_ID = "admin-impersonation-test";

    @AfterEach
    void clearSession() {
        AuthSessions.clearUserTokens(ADMIN_ID);
    }

    @Test
    void disabledUserCannotBeUsedForAdminIdentitySwitching() {
        UserRepository users = mock(UserRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        AuthController controller = new AuthController(users, permissions, mock(ActivityLogRepository.class));
        User disabled = User.builder()
                .userId("disabled-1")
                .name("停用用户")
                .role("designer")
                .status("disabled")
                .build();
        when(permissions.has("admin", "admin.identity.switch")).thenReturn(true);
        when(users.findByUserId("disabled-1")).thenReturn(Optional.of(disabled));
        String token = AuthSessions.generateToken(ADMIN_ID, "admin", "管理员");

        var response = controller.impersonate(token, Map.of("userId", "disabled-1"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("停用用户不能切换视角", response.getBody().get("error"));
        assertEquals(ADMIN_ID, AuthSessions.validateToken(token).userId());
    }

    @Test
    void configuredEmergencyAdminCanLogInWithoutDatabaseUser() {
        UserRepository users = mock(UserRepository.class);
        AuthController controller = new AuthController(
                users,
                mock(PermissionService.class),
                mock(ActivityLogRepository.class),
                null,
                "break-glass",
                "test-only-password");

        var response = controller.login(
                Map.of("id", "break-glass", "password", "test-only-password"), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("admin", response.getBody().get("role"));
        AuthSessions.clearUserTokens("break-glass");
    }
}
