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
import java.util.Map;
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

    @Test
    void renamingPlannerInvalidatesOldIdSessionWithoutChangingPersonalScope() {
        UserRepository users = mock(UserRepository.class);
        UserService userService = mock(UserService.class);
        User planner = User.builder()
                .id(43L)
                .userId("rename_old_planner")
                .name("企划")
                .role("planner")
                .projectDataScope("own")
                .build();
        when(users.findById(43L)).thenReturn(Optional.of(planner));
        String oldToken = AuthSessions.generateToken(planner.getUserId(), planner.getRole(), planner.getName());
        String unrelated = AuthSessions.generateToken("rename_unrelated", "planner", "其他企划");
        try {
            AdminService service = new AdminService(
                    mock(SystemConfigRepository.class), users, mock(RoleRepository.class), userService);
            service.updateUser(43L, Map.of("userId", "rename_new_planner"));
            assertNull(AuthSessions.validateToken(oldToken));
            assertNotNull(AuthSessions.validateToken(unrelated));
            assertEquals("own", planner.getProjectDataScope());
            verify(userService).refreshCache();
        } finally {
            AuthSessions.remove(oldToken);
            AuthSessions.remove(unrelated);
        }
    }

    @Test
    void renameAndRoleChangeInvalidateBothOldAndNewAccountSessions() {
        UserRepository users = mock(UserRepository.class);
        User planner = User.builder()
                .id(44L)
                .userId("rename_role_old")
                .name("企划")
                .role("planner")
                .build();
        when(users.findById(44L)).thenReturn(Optional.of(planner));
        String oldToken = AuthSessions.generateToken("rename_role_old", "planner", "企划");
        String newIdToken = AuthSessions.generateToken("rename_role_new", "planner", "企划");
        try {
            AdminService service = new AdminService(
                    mock(SystemConfigRepository.class), users, mock(RoleRepository.class), mock(UserService.class));
            service.updateUser(44L, Map.of("userId", "rename_role_new", "role", "designer"));
            assertNull(AuthSessions.validateToken(oldToken));
            assertNull(AuthSessions.validateToken(newIdToken));
            assertEquals("designer", planner.getRole());
        } finally {
            AuthSessions.remove(oldToken);
            AuthSessions.remove(newIdToken);
        }
    }
}
