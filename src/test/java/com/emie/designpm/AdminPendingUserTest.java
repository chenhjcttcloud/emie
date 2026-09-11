package com.emie.designpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.RoleRepository;
import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.admin.service.AdminService;
import com.emie.designpm.admin.service.UserService;
import com.emie.designpm.auth.AuthSessions;
import com.emie.designpm.entity.User;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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

        String oldToken = AuthSessions.generateToken(pending.getUserId(), pending.getRole(), pending.getName());
        AdminService service =
                new AdminService(mock(SystemConfigRepository.class), users, mock(RoleRepository.class), userService);

        User updated = service.updateUserRole(42L, "designer", "admin");

        assertEquals("designer", updated.getRole());
        assertEquals("active", updated.getStatus());
        assertEquals("设计师", updated.getTitle());
        assertNull(AuthSessions.validateToken(oldToken));
        verify(userService).refreshCache();
    }
}
