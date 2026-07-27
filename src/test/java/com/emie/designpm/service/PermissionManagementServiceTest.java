package com.emie.designpm.service;

import com.emie.designpm.entity.PermissionAuditLog;
import com.emie.designpm.entity.PermissionDefinition;
import com.emie.designpm.entity.PermissionVersion;
import com.emie.designpm.entity.Role;
import com.emie.designpm.entity.RolePermission;
import com.emie.designpm.repository.PermissionAuditLogRepository;
import com.emie.designpm.repository.PermissionDefinitionRepository;
import com.emie.designpm.repository.PermissionVersionRepository;
import com.emie.designpm.repository.RolePermissionRepository;
import com.emie.designpm.repository.RolePermissionScopeRepository;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionManagementServiceTest {

    @Test
    void updatingRolePersistsExplicitAllowAndDenyVersionAndAudit() {
        PermissionDefinitionRepository definitions = mock(PermissionDefinitionRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        RolePermissionRepository assignments = mock(RolePermissionRepository.class);
        RolePermissionScopeRepository scopes = mock(RolePermissionScopeRepository.class);
        PermissionVersionRepository versions = mock(PermissionVersionRepository.class);
        PermissionAuditLogRepository audits = mock(PermissionAuditLogRepository.class);
        UserRepository users = mock(UserRepository.class);
        PermissionService resolver = mock(PermissionService.class);

        PermissionDefinition dashboard = definition(1L, "page.dashboard.view");
        PermissionDefinition admin = definition(2L, "page.admin.view");
        when(definitions.findByEnabledTrueOrderByModuleAscCodeAsc()).thenReturn(List.of(dashboard, admin));

        Role role = Role.builder().id(5L).name("custom").displayName("旧名称")
                .description("").permissions("").isSystem(false).build();
        when(roles.findById(5L)).thenReturn(Optional.of(role));
        when(roles.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignments.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scopes.findByRoleId(5L)).thenReturn(List.of());
        when(resolver.capabilities("custom")).thenReturn(Map.of(
                "permissions", List.of("page.dashboard.view", "page.admin.view")));

        PermissionVersion version = new PermissionVersion();
        version.setSubjectType("role");
        version.setSubjectKey("custom");
        version.setVersion(1L);
        when(versions.findForUpdateBySubjectTypeAndSubjectKey("role", "custom"))
                .thenReturn(Optional.of(version));
        when(versions.findBySubjectTypeAndSubjectKey("role", "custom"))
                .thenAnswer(invocation -> Optional.of(version));
        when(versions.save(any(PermissionVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PermissionManagementService service = new PermissionManagementService(
                definitions, roles, assignments, scopes, versions, audits, users, resolver, new ObjectMapper());

        service.updateRole(5L, "新名称", "说明", List.of("page.dashboard.view"),
                new PermissionManagementService.Actor("admin-1", "管理员", "调整测试角色", "127.0.0.1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RolePermission>> assignmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(assignments).saveAll(assignmentCaptor.capture());
        Map<String, String> effects = assignmentCaptor.getValue().stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.getPermission().getCode(), RolePermission::getEffect));
        assertEquals("allow", effects.get("page.dashboard.view"));
        assertEquals("deny", effects.get("page.admin.view"));
        assertEquals(2L, version.getVersion());

        ArgumentCaptor<PermissionAuditLog> auditCaptor = ArgumentCaptor.forClass(PermissionAuditLog.class);
        verify(audits).save(auditCaptor.capture());
        assertEquals("调整测试角色", auditCaptor.getValue().getReason());
        assertTrue(auditCaptor.getValue().getBeforeData().contains("旧名称"));
        assertTrue(auditCaptor.getValue().getAfterData().contains("新名称"));
    }

    private PermissionDefinition definition(Long id, String code) {
        PermissionDefinition definition = new PermissionDefinition();
        definition.setId(id);
        definition.setCode(code);
        definition.setName(code);
        definition.setModule("测试");
        definition.setRiskLevel("normal");
        definition.setEnabled(true);
        return definition;
    }
}
