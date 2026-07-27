package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.Role;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.PermissionAuditLogRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionGovernanceServiceTest {

    @Test
    void simulatorExplainsFunctionalAndProjectScopeDecision() {
        PermissionService permissions = mock(PermissionService.class);
        UserRepository users = mock(UserRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        User user = User.builder().userId("sales-1").name("销售一").role("sales").build();
        Project project = new Project();
        project.setId(9L);
        when(users.findByUserId("sales-1")).thenReturn(Optional.of(user));
        when(projects.findById(9L)).thenReturn(Optional.of(project));
        when(permissions.has("sales", "project.channel.edit")).thenReturn(true);
        when(permissions.scopes("sales", "project.channel.edit")).thenReturn(List.of("own"));
        when(access.canView(org.mockito.ArgumentMatchers.eq(project), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        PermissionGovernanceService service = service(permissions, users, projects, access, mock(RoleRepository.class));

        Map<String, Object> result = service.simulate("sales-1", "project.channel.edit", 9L);

        assertFalse((Boolean) result.get("allowed"));
        assertTrue((Boolean) result.get("functionalPermission"));
        assertFalse((Boolean) result.get("scopeMatched"));
        assertEquals(3, ((List<?>) result.get("reasonChain")).size());
    }

    @Test
    void anomalyDetectorFindsUnusedAndOverPrivilegedCustomRole() {
        PermissionService permissions = mock(PermissionService.class);
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        Role role = Role.builder().id(8L).name("custom_power").displayName("自定义强权限")
                .isSystem(false).build();
        when(roles.findAll()).thenReturn(List.of(role));
        when(users.findByRole("custom_power")).thenReturn(List.of());
        when(permissions.capabilities("custom_power")).thenReturn(Map.of(
                "permissions", java.util.stream.IntStream.range(0, 31).mapToObj(i -> "p." + i).toList()));
        PermissionGovernanceService service = service(permissions, users, mock(ProjectRepository.class),
                mock(ProjectAccessService.class), roles);

        List<Map<String, Object>> result = service.anomalies();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(item -> "unused_role".equals(item.get("type"))));
        assertTrue(result.stream().anyMatch(item -> "excessive_permissions".equals(item.get("type"))));
    }

    private PermissionGovernanceService service(PermissionService permissions, UserRepository users,
                                                 ProjectRepository projects, ProjectAccessService access,
                                                 RoleRepository roles) {
        return new PermissionGovernanceService(permissions, mock(PermissionManagementService.class),
                mock(PermissionAuditLogRepository.class), roles, users, projects, access);
    }
}
