package com.emie.designpm.service;

import com.emie.designpm.entity.Role;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.PermissionVersionRepository;
import com.emie.designpm.repository.RolePermissionRepository;
import com.emie.designpm.repository.RolePermissionScopeRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionServiceTest {

    @Test
    void compatibilityMatrixPreservesCurrentCreateEntries() {
        RoleRepository roles = mock(RoleRepository.class);
        RolePermissionRepository assignments = emptyAssignments();
        PermissionVersionRepository versions = emptyVersions();
        when(roles.findByNameIgnoreCase(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        PermissionService service = new PermissionService(roles, assignments, versions);

        assertPermissions(service.capabilities("sales"),
                "page.dashboard.view", "project.channel.create", "project.channel.edit",
                "design_requirement.create");
        assertPermissions(service.capabilities("planner"),
                "project.regular.create", "project.channel.edit", "project.regular.edit",
                "subtask.create", "design_requirement.create", "page.subtasks.department.view");
        assertPermissions(service.capabilities("Promotion"), "design_requirement.create");

        assertFalse(permissions(service.capabilities("admin")).contains("project.channel.create"),
                "管理员兼容模板不应新增项目创建入口");
        assertFalse(permissions(service.capabilities("designer")).contains("design_requirement.create"),
                "设计师兼容模板不应新增需求创建入口");
        assertTrue(permissions(service.capabilities("pending")).isEmpty(),
                "待授权角色不应获得任何能力");
    }

    @Test
    void configuredLegacyPermissionsAreTranslatedWithoutLosingOriginalCode() {
        RoleRepository roles = mock(RoleRepository.class);
        RolePermissionRepository assignments = emptyAssignments();
        PermissionVersionRepository versions = emptyVersions();
        Role custom = Role.builder()
                .name("custom")
                .displayName("自定义")
                .permissions("dashboard:view,admin:users,custom.permission")
                .updatedAt(LocalDateTime.of(2026, 7, 24, 12, 0))
                .build();
        when(roles.findByNameIgnoreCase("custom")).thenReturn(Optional.of(custom));

        Map<String, Object> result = new PermissionService(roles, assignments, versions).capabilities("custom");
        List<String> permissions = permissions(result);

        assertTrue(permissions.contains("dashboard:view"));
        assertTrue(permissions.contains("page.dashboard.view"));
        assertTrue(permissions.contains("admin.user.manage"));
        assertTrue(permissions.contains("custom.permission"));
        assertTrue(((Number) result.get("permissionVersion")).longValue() > 1L);
    }

    private void assertPermissions(Map<String, Object> result, String... expected) {
        List<String> permissions = permissions(result);
        for (String permission : expected) {
            assertTrue(permissions.contains(permission), "缺少权限 " + permission);
        }
    }

    @Test
    void normalizedAssignmentsAndExplicitDenyParticipateInResolution() {
        RoleRepository roles = mock(RoleRepository.class);
        RolePermissionRepository assignments = mock(RolePermissionRepository.class);
        PermissionVersionRepository versions = emptyVersions();
        when(roles.findByNameIgnoreCase("sales")).thenReturn(Optional.empty());
        when(assignments.findAllowedPermissionCodes("sales")).thenReturn(List.of("custom.allowed"));
        when(assignments.findDeniedPermissionCodes("sales")).thenReturn(List.of("project.channel.create"));

        List<String> permissions = permissions(
                new PermissionService(roles, assignments, versions).capabilities("sales"));

        assertTrue(permissions.contains("custom.allowed"));
        assertFalse(permissions.contains("project.channel.create"),
                "规范化权限表中的明确禁止应覆盖兼容角色模板");
    }

    @Test
    void configuredDataScopeOverridesCompatibilityScopeAndIsReturnedToFrontend() {
        RoleRepository roles = mock(RoleRepository.class);
        RolePermissionRepository assignments = emptyAssignments();
        PermissionVersionRepository versions = emptyVersions();
        RolePermissionScopeRepository scopes = mock(RolePermissionScopeRepository.class);
        when(roles.findByNameIgnoreCase("sales")).thenReturn(Optional.empty());
        when(assignments.findAllowedPermissionCodes("sales")).thenReturn(List.of("project.view"));
        when(scopes.findScopeTypes("sales", "project.view")).thenReturn(List.of("own"));

        PermissionService service = new PermissionService(roles, assignments, versions, scopes);

        assertTrue(service.scopes("sales", "project.view").contains("own"));
        assertFalse(service.scopes("sales", "project.view").contains("department"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> capabilityScopes =
                (Map<String, List<String>>) service.capabilities("sales").get("scopes");
        assertEquals(List.of("own"), capabilityScopes.get("project.view"));
    }

    private RolePermissionRepository emptyAssignments() {
        RolePermissionRepository assignments = mock(RolePermissionRepository.class);
        when(assignments.findAllowedPermissionCodes(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(assignments.findDeniedPermissionCodes(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        return assignments;
    }

    private PermissionVersionRepository emptyVersions() {
        PermissionVersionRepository versions = mock(PermissionVersionRepository.class);
        when(versions.findBySubjectTypeAndSubjectKey(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        return versions;
    }

    @SuppressWarnings("unchecked")
    private List<String> permissions(Map<String, Object> result) {
        return (List<String>) result.get("permissions");
    }
}
