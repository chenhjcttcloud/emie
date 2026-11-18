package com.emie.designpm.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.PermissionAuditLogRepository;
import com.emie.designpm.admin.repository.PermissionVersionRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.PermissionAuditLog;
import com.emie.designpm.entity.PermissionVersion;
import com.emie.designpm.entity.User;
import com.emie.designpm.reference.repository.DepartmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserProjectScopeServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PermissionVersionRepository versions = mock(PermissionVersionRepository.class);
    private final PermissionAuditLogRepository audits = mock(PermissionAuditLogRepository.class);
    private final UserProjectScopeService scopes =
            new UserProjectScopeService(users, versions, audits, new ObjectMapper());

    @Test
    void userOverrideRestrictsOnlyThatPlannerAndProjectDataPermissions() {
        User xiaojie = User.builder()
                .userId("xiaojie")
                .role("planner")
                .projectDataScope("own")
                .build();
        when(users.findByUserId("xiaojie")).thenReturn(Optional.of(xiaojie));
        when(users.findByUserId("other"))
                .thenReturn(Optional.of(
                        User.builder().userId("other").role("planner").build()));
        assertEquals(List.of("own"), scopes.resolveScopes("planner", "xiaojie", "project.view", List.of("role_team")));
        assertEquals(List.of("own"), scopes.resolveScopes("planner", "xiaojie", "subtask.view", List.of("role_team")));
        assertEquals(
                List.of("role_team"), scopes.resolveScopes("planner", "other", "project.view", List.of("role_team")));
        assertEquals(List.of("all"), scopes.resolveScopes("admin", "xiaojie", "project.view", List.of("all")));
        assertEquals(List.of("all"), scopes.resolveScopes("planner", "xiaojie", "file.download", List.of("all")));
        xiaojie.setProjectDataScope(null);
        assertEquals(
                List.of("role_team"), scopes.resolveScopes("planner", "xiaojie", "project.view", List.of("role_team")));
    }

    @Test
    void missingPlannerAccountCannotInheritSharedScopeFromAnOldSession() {
        assertEquals(
                List.of("own"),
                scopes.resolveScopes("planner", "renamed-old-id", "project.view", List.of("role_team")));
        assertEquals(
                List.of("own"),
                scopes.applyScopes("planner", "renamed-old-id", Map.of("project.view", List.of("role_team")))
                        .get("project.view"));
        when(users.findByUserId("existing"))
                .thenReturn(Optional.of(
                        User.builder().userId("existing").role("planner").build()));
        assertEquals(
                List.of("role_team"),
                scopes.resolveScopes("planner", "existing", "project.view", List.of("role_team")));
    }

    @Test
    void departmentCapabilitiesRequireActivePlannerDepartmentHeadAndPreserveStoredChoice() {
        DepartmentRepository departments = mock(DepartmentRepository.class);
        scopes.setDepartmentRepository(departments);
        User user = User.builder()
                .userId("head")
                .role("planner")
                .projectDataScope("department")
                .build();
        when(users.findByUserId("head")).thenReturn(Optional.of(user));
        Map<String, List<String>> inherited = Map.of("project.view", List.of("role_team"));
        assertEquals(
                List.of("own"), scopes.applyScopes("planner", "head", inherited).get("project.view"));
        Department department = Department.builder()
                .headUserId("head")
                .role("planner")
                .active(false)
                .build();
        when(departments.findByHeadUserId("head")).thenReturn(Optional.of(department));
        assertEquals(List.of("own"), scopes.resolveScopes("planner", "head", "project.view", List.of("role_team")));
        department.setActive(true);
        assertEquals(
                List.of("department"),
                scopes.applyScopes("planner", "head", inherited).get("project.view"));
        assertEquals("department", user.getProjectDataScope());
    }

    @Test
    void effectiveCapabilitiesRetainDeniedPermissionsAndChangeUserCacheVersion() {
        var roles = mock(com.emie.designpm.admin.repository.RoleRepository.class);
        var assignments = mock(com.emie.designpm.admin.repository.RolePermissionRepository.class);
        var roleScopes = mock(com.emie.designpm.admin.repository.RolePermissionScopeRepository.class);
        var role = com.emie.designpm.entity.Role.builder()
                .name("planner")
                .permissions("project:view,project.detail.view,subtask.view")
                .build();
        when(roles.findByNameIgnoreCase("planner")).thenReturn(Optional.of(role));
        when(assignments.findDeniedPermissionCodes("planner")).thenReturn(List.of("project.detail.view"));
        when(users.findByUserId("xiaojie"))
                .thenReturn(Optional.of(
                        User.builder().userId("xiaojie").projectDataScope("own").build()));
        PermissionVersion version = new PermissionVersion();
        version.setVersion(6L);
        when(versions.findBySubjectTypeAndSubjectKey("user", "xiaojie")).thenReturn(Optional.of(version));
        PermissionService permissions = new PermissionService(roles, assignments, versions, roleScopes);
        permissions.setUserProjectScopeService(scopes);
        Map<String, Object> capabilities = permissions.capabilities("planner", "xiaojie");
        Map<?, ?> effective = (Map<?, ?>) capabilities.get("scopes");
        assertEquals(List.of("own"), effective.get("project.view"));
        assertFalse(effective.containsKey("project.detail.view"));
        assertEquals("1:6", capabilities.get("permissionVersion"));
        verify(users, times(1)).findByUserId("xiaojie");
    }

    @Test
    void changingUserScopeRequiresReasonAndPersistsAuditAndUserVersion() {
        User user = User.builder().id(7L).userId("xiaojie").role("planner").build();
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.findByUserIdForUpdate("xiaojie")).thenReturn(Optional.of(user));
        PermissionVersion version = new PermissionVersion();
        version.setVersion(4L);
        when(versions.findForUpdateBySubjectTypeAndSubjectKey("user", "xiaojie"))
                .thenReturn(Optional.of(version));
        var actor = new PermissionManagementService.Actor("admin", "管理员", "限定项目范围", "127.0.0.1");
        Map<String, Object> saved = scopes.updateScope(7L, "own", actor);
        assertEquals("own", user.getProjectDataScope());
        assertEquals(5L, saved.get("permissionVersion"));
        ArgumentCaptor<PermissionAuditLog> audit = ArgumentCaptor.forClass(PermissionAuditLog.class);
        verify(audits).save(audit.capture());
        assertEquals("user", audit.getValue().getTargetType());
        assertTrue(audit.getValue().getBeforeData().contains("inherit"));
        assertTrue(audit.getValue().getAfterData().contains("own"));
        assertEquals("限定项目范围", audit.getValue().getReason());
        assertThrows(IllegalArgumentException.class, () -> scopes.updateScope(7L, "all", actor));
        assertThrows(
                IllegalArgumentException.class,
                () -> scopes.updateScope(
                        7L, "own", new PermissionManagementService.Actor("admin", "管理员", "", "127.0.0.1")));
        user.setRole("admin");
        assertThrows(IllegalArgumentException.class, () -> scopes.updateScope(7L, "own", actor));
    }
}
