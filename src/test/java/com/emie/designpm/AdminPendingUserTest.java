package com.emie.designpm;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.service.AdminService;
import com.emie.designpm.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminPendingUserTest {

    @Test
    void assigningBusinessRoleActivatesPendingUserAndInvalidatesOldSession() {
        UserRepository users = mock(UserRepository.class);
        UserService userService = mock(UserService.class);
        User pending = User.builder()
                .id(42L)
                .userId("feishu_pending_user")
                .name("待授权员工")
                .role("pending")
                .status("pending")
                .build();
        when(users.findById(42L)).thenReturn(Optional.of(pending));
        when(users.save(pending)).thenReturn(pending);

        String oldToken = AuthController.generateToken(pending.getUserId(), pending.getRole(), pending.getName());
        AdminService service = new AdminService(mock(SystemConfigRepository.class), users,
                mock(RoleRepository.class), userService);

        User updated = service.updateUserRole(42L, "designer", "admin");

        assertEquals("designer", updated.getRole());
        assertEquals("active", updated.getStatus());
        assertEquals("设计师", updated.getTitle());
        assertNull(AuthController.validateToken(oldToken));
        verify(userService).refreshCache();
    }
}
