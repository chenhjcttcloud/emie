package com.emie.designpm.controller;

import com.emie.designpm.entity.User;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerImpersonationTest {
    private static final String ADMIN_ID = "admin-impersonation-test";

    @AfterEach
    void clearSession() {
        AuthController.clearUserTokens(ADMIN_ID);
    }

    @Test
    void disabledUserCannotBeUsedForAdminIdentitySwitching() {
        UserRepository users = mock(UserRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        AuthController controller = new AuthController(users, permissions, mock(ActivityLogRepository.class));
        User disabled = User.builder().userId("disabled-1").name("停用用户")
                .role("designer").status("disabled").build();
        when(permissions.has("admin", "admin.identity.switch")).thenReturn(true);
        when(users.findByUserId("disabled-1")).thenReturn(Optional.of(disabled));
        String token = AuthController.generateToken(ADMIN_ID, "admin", "管理员");

        var response = controller.impersonate(token, Map.of("userId", "disabled-1"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("停用用户不能切换视角", response.getBody().get("error"));
        assertEquals(ADMIN_ID, AuthController.validateToken(token).userId());
    }
}
