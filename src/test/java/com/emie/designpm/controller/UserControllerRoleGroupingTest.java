package com.emie.designpm.controller;

import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerRoleGroupingTest {

    @Test
    void usersEndpointIncludesCustomRolesAndKeepsStandardEmptyGroups() {
        UserService users = mock(UserService.class);
        UserController controller = new UserController(users, mock(DepartmentRepository.class),
                mock(RoleRepository.class));
        User promotion = User.builder()
                .id(1L)
                .userId("promotion_user")
                .name("产品推广用户")
                .role("Promotion")
                .build();
        User pending = User.builder()
                .id(2L)
                .userId("pending_user")
                .name("待分配用户")
                .role("pending")
                .build();
        User disabled = User.builder()
                .id(3L)
                .userId("disabled_designer")
                .name("停用设计师")
                .role("designer")
                .status("disabled")
                .build();
        when(users.getAllUsers()).thenReturn(List.of(promotion, pending, disabled));

        Map<String, List<Map<String, String>>> result = controller.getUsers().getBody();

        assertTrue(result.containsKey("sales"));
        assertTrue(result.get("sales").isEmpty());
        assertEquals("产品推广用户", result.get("Promotion").get(0).get("name"));
        assertEquals("active", result.get("Promotion").get(0).get("status"));
        assertTrue(result.get("designer").isEmpty());
        assertFalse(result.containsKey("pending"));
    }
}
