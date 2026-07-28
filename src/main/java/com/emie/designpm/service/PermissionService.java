package com.emie.designpm.service;

import com.emie.designpm.entity.Role;
import com.emie.designpm.repository.PermissionVersionRepository;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.RolePermissionRepository;
import com.emie.designpm.repository.RolePermissionScopeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 统一权限解析入口。
 *
 * <p>第一阶段以兼容模式返回能力清单，不改变既有后端业务授权。角色配置和当前角色行为
 * 模板取并集，确保接入过程中不会因为历史角色权限数据缺失而突然隐藏现有入口。</p>
 */
@Service
public class PermissionService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionVersionRepository permissionVersionRepository;
    private final RolePermissionScopeRepository rolePermissionScopeRepository;

    @Autowired
    public PermissionService(RoleRepository roleRepository,
                             RolePermissionRepository rolePermissionRepository,
                             PermissionVersionRepository permissionVersionRepository,
                             RolePermissionScopeRepository rolePermissionScopeRepository) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.permissionVersionRepository = permissionVersionRepository;
        this.rolePermissionScopeRepository = rolePermissionScopeRepository;
    }

    /** 保留给不关心数据范围的轻量单元测试。 */
    public PermissionService(RoleRepository roleRepository,
                             RolePermissionRepository rolePermissionRepository,
                             PermissionVersionRepository permissionVersionRepository) {
        this(roleRepository, rolePermissionRepository, permissionVersionRepository, null);
    }

    public Map<String, Object> capabilities(String rawRole) {
        String roleName = PermissionCatalog.normalizeRole(rawRole);
        Role role = resolveRole(roleName);
        String assignmentRoleName = role != null ? role.getName() : roleName;

        LinkedHashSet<String> permissions = new LinkedHashSet<>(
                PermissionCatalog.compatibilityPermissions(roleName));
        permissions.addAll(rolePermissionRepository.findAllowedPermissionCodes(assignmentRoleName));
        if (role != null) {
            permissions.addAll(PermissionCatalog.translateConfiguredPermissions(role.getPermissions()));
        }
        permissions.removeAll(rolePermissionRepository.findDeniedPermissionCodes(assignmentRoleName));
        // 防止旧版角色编辑在未展示新增能力时写入 deny，造成管理员无法恢复身份、
        // 一线角色无法上传或读取自己刚上传的文件。
        permissions.addAll(PermissionCatalog.mandatoryPermissions(roleName));

        List<String> sorted = new ArrayList<>(permissions);
        sorted.sort(String::compareTo);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", roleName);
        result.put("mode", PermissionCatalog.MODE);
        result.put("permissionVersion", permissionVersion(roleName, role));
        result.put("permissions", sorted);
        Map<String, List<String>> scopes = new LinkedHashMap<>();
        for (String permission : sorted) {
            List<String> resolved = resolveScopes(roleName, assignmentRoleName, permission);
            if (!resolved.isEmpty()) scopes.put(permission, resolved);
        }
        result.put("scopes", scopes);
        return result;
    }

    public boolean has(String rawRole, String permission) {
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) capabilities(rawRole).get("permissions");
        return permissions.contains(permission);
    }

    public List<String> scopes(String rawRole, String permission) {
        String roleName = PermissionCatalog.normalizeRole(rawRole);
        Role role = resolveRole(roleName);
        return resolveScopes(roleName, role != null ? role.getName() : roleName, permission);
    }

    private List<String> resolveScopes(String normalizedRole, String assignmentRoleName, String permission) {
        List<String> configured = rolePermissionScopeRepository == null ? List.of()
                : rolePermissionScopeRepository.findScopeTypes(assignmentRoleName, permission);
        if (!configured.isEmpty()) return configured;
        return PermissionCatalog.compatibilityScopes(normalizedRole, permission).stream().sorted().toList();
    }

    private long permissionVersion(String roleName, Role role) {
        Long storedVersion = permissionVersionRepository
                .findBySubjectTypeAndSubjectKey("role", roleName)
                .map(com.emie.designpm.entity.PermissionVersion::getVersion)
                .orElse(null);
        if (storedVersion != null) return Math.max(1L, storedVersion);
        if (role == null || role.getUpdatedAt() == null) return 1L;
        return Math.max(1L, role.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    private Role resolveRole(String normalizedRole) {
        return roleRepository.findByNameIgnoreCase(normalizedRole)
                .orElseGet(() -> roleRepository.findAll().stream()
                        .filter(role -> PermissionCatalog.normalizeRole(role.getName()).equals(normalizedRole))
                        .findFirst()
                        .orElse(null));
    }
}
