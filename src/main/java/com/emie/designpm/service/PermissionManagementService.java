package com.emie.designpm.service;

import com.emie.designpm.entity.PermissionAuditLog;
import com.emie.designpm.entity.PermissionDefinition;
import com.emie.designpm.entity.PermissionVersion;
import com.emie.designpm.entity.Role;
import com.emie.designpm.entity.RolePermission;
import com.emie.designpm.entity.RolePermissionScope;
import com.emie.designpm.repository.PermissionAuditLogRepository;
import com.emie.designpm.repository.PermissionDefinitionRepository;
import com.emie.designpm.repository.PermissionVersionRepository;
import com.emie.designpm.repository.RolePermissionRepository;
import com.emie.designpm.repository.RolePermissionScopeRepository;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PermissionManagementService {

    private final PermissionDefinitionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RolePermissionScopeRepository scopeRepository;
    private final PermissionVersionRepository versionRepository;
    private final PermissionAuditLogRepository auditRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    public PermissionManagementService(PermissionDefinitionRepository permissionRepository,
                                       RoleRepository roleRepository,
                                       RolePermissionRepository rolePermissionRepository,
                                       RolePermissionScopeRepository scopeRepository,
                                       PermissionVersionRepository versionRepository,
                                       PermissionAuditLogRepository auditRepository,
                                       UserRepository userRepository,
                                       PermissionService permissionService,
                                       ObjectMapper objectMapper) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.scopeRepository = scopeRepository;
        this.versionRepository = versionRepository;
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> definitions() {
        return permissionRepository.findByEnabledTrueOrderByModuleAscCodeAsc().stream()
                .map(this::definitionMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> roles() {
        return roleRepository.findAll().stream()
                .sorted(Comparator.comparing(Role::getId))
                .map(this::roleMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createRole(String name, String displayName, String description,
                                          List<String> permissionCodes, Actor actor) {
        return createRole(name, displayName, description, permissionCodes, Map.of(), actor);
    }

    @Transactional
    public Map<String, Object> createRole(String name, String displayName, String description,
                                          List<String> permissionCodes, Map<String, List<String>> scopes,
                                          Actor actor) {
        String roleName = validateRoleName(name);
        if (roleRepository.findAll().stream()
                .map(Role::getName)
                .map(PermissionCatalog::normalizeRole)
                .anyMatch(roleName::equals)) {
            throw new IllegalArgumentException("角色标识「" + roleName + "」已存在");
        }
        validateDisplayName(displayName);
        validateReason(actor.reason());

        Role role = Role.builder()
                .name(roleName)
                .displayName(displayName.trim())
                .description(description != null ? description.trim() : "")
                .permissions("")
                .isSystem(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        role = roleRepository.save(role);
        Set<String> selected = replaceAssignments(role, permissionCodes);
        Map<String, List<String>> savedScopes = replaceScopes(role, selected, scopes);
        role.setPermissions(String.join(",", selected));
        roleRepository.save(role);
        long version = incrementVersion(roleName);
        writeAudit(actor, "role.create", roleName, null, snapshot(role, selected, savedScopes, version));
        return roleMap(role);
    }

    @Transactional
    public Map<String, Object> updateRole(Long roleId, String displayName, String description,
                                          List<String> permissionCodes, Actor actor) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        return updateRole(roleId, displayName, description, permissionCodes, currentScopes(role), actor);
    }

    @Transactional
    public Map<String, Object> updateRole(Long roleId, String displayName, String description,
                                          List<String> permissionCodes, Map<String, List<String>> scopes,
                                          Actor actor) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        validateDisplayName(displayName);
        validateReason(actor.reason());
        Map<String, Object> before = snapshot(role, currentSelectedPermissions(role.getName()),
                currentScopes(role), currentVersion(role.getName()));

        role.setDisplayName(displayName.trim());
        role.setDescription(description != null ? description.trim() : "");
        Set<String> selected = replaceAssignments(role, permissionCodes);
        Map<String, List<String>> savedScopes = replaceScopes(role, selected, scopes);
        role.setPermissions(String.join(",", selected));
        roleRepository.save(role);
        long version = incrementVersion(role.getName());
        writeAudit(actor, "role.update", role.getName(), before, snapshot(role, selected, savedScopes, version));
        return roleMap(role);
    }

    @Transactional
    public void deleteRole(Long roleId, Actor actor) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new IllegalArgumentException("系统内置角色不可删除");
        }
        if (!userRepository.findByRole(role.getName()).isEmpty()) {
            throw new IllegalArgumentException("该角色下还有用户，无法删除。请先变更用户的角色");
        }
        validateReason(actor.reason());
        Map<String, Object> before = snapshot(role, currentSelectedPermissions(role.getName()),
                currentScopes(role), currentVersion(role.getName()));
        rolePermissionRepository.deleteByRoleId(roleId);
        roleRepository.delete(role);
        writeAudit(actor, "role.delete", role.getName(), before, null);
    }

    @Transactional
    public Map<String, Object> rollbackRole(Long roleId, Long auditId, Actor actor) {
        validateReason(actor.reason());
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        PermissionAuditLog source = auditRepository.findById(auditId)
                .orElseThrow(() -> new IllegalArgumentException("权限历史版本不存在"));
        if (!"role".equals(source.getTargetType()) || !role.getName().equals(source.getTargetKey())
                || source.getAfterData() == null) {
            throw new IllegalArgumentException("该历史版本不属于当前角色或不可回滚");
        }
        Map<String, Object> target;
        try {
            target = objectMapper.readValue(source.getAfterData(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("历史权限快照损坏，无法回滚");
        }
        Map<String, Object> before = snapshot(role, currentSelectedPermissions(role.getName()),
                currentScopes(role), currentVersion(role.getName()));
        role.setDisplayName(String.valueOf(target.getOrDefault("displayName", role.getDisplayName())));
        role.setDescription(String.valueOf(target.getOrDefault("description", role.getDescription())));
        List<String> permissions = stringList(target.get("permissions"));
        Map<String, List<String>> scopes = scopeMap(target.get("scopes"));
        Set<String> selected = replaceAssignments(role, permissions);
        Map<String, List<String>> savedScopes = replaceScopes(role, selected, scopes);
        role.setPermissions(String.join(",", selected));
        roleRepository.save(role);
        long version = incrementVersion(role.getName());
        Map<String, Object> after = snapshot(role, selected, savedScopes, version);
        writeAudit(actor, "role.rollback", role.getName(), before, after);
        return roleMap(role);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private Map<String, List<String>> scopeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        map.forEach((key, scopes) -> result.put(String.valueOf(key), stringList(scopes)));
        return result;
    }

    private Set<String> replaceAssignments(Role role, List<String> requestedCodes) {
        List<PermissionDefinition> definitions = permissionRepository.findByEnabledTrueOrderByModuleAscCodeAsc();
        Set<String> validCodes = new LinkedHashSet<>();
        for (PermissionDefinition definition : definitions) validCodes.add(definition.getCode());

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (requestedCodes != null) {
            for (String code : requestedCodes) {
                if (code != null && validCodes.contains(code.trim())) selected.add(code.trim());
                else if (code != null && !code.isBlank()) throw new IllegalArgumentException("未知权限编码：" + code);
            }
        }
        // 基础恢复/文件能力不得因旧页面未展示新增权限而被保存为 deny。
        selected.addAll(PermissionCatalog.mandatoryPermissions(role.getName()));

        rolePermissionRepository.deleteByRoleId(role.getId());
        rolePermissionRepository.flush();
        List<RolePermission> assignments = new ArrayList<>(definitions.size());
        for (PermissionDefinition definition : definitions) {
            RolePermission assignment = new RolePermission();
            assignment.setRole(role);
            assignment.setPermission(definition);
            assignment.setEffect(selected.contains(definition.getCode()) ? "allow" : "deny");
            assignments.add(assignment);
        }
        rolePermissionRepository.saveAll(assignments);
        return selected;
    }

    private Map<String, List<String>> replaceScopes(Role role, Set<String> selected,
                                                     Map<String, List<String>> requested) {
        Set<String> scopePermissions = Set.of("project.view", "project.detail.view", "subtask.view");
        Set<String> validScopes = Set.of("all", "own", "participated", "department", "role_team");
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        List<RolePermission> assignments = rolePermissionRepository.findByRoleId(role.getId());
        for (RolePermission assignment : assignments) {
            String code = assignment.getPermission().getCode();
            if (!selected.contains(code) || !scopePermissions.contains(code)) continue;
            List<String> values = requested == null ? List.of() : requested.getOrDefault(code, List.of());
            LinkedHashSet<String> clean = values.stream()
                    .filter(java.util.Objects::nonNull).map(String::trim)
                    .filter(validScopes::contains)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (clean.isEmpty()) {
                clean.addAll(PermissionCatalog.compatibilityScopes(role.getName(), code));
            }
            if (clean.isEmpty()) clean.add("own");
            normalized.put(code, clean.stream().sorted().toList());
            for (String scopeType : clean) {
                RolePermissionScope scope = new RolePermissionScope();
                scope.setRolePermission(assignment);
                scope.setScopeType(scopeType);
                scope.setScopeValue("");
                scopeRepository.save(scope);
            }
        }
        return normalized;
    }

    private Map<String, List<String>> currentScopes(Role role) {
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (RolePermissionScope scope : scopeRepository.findByRoleId(role.getId())) {
            grouped.computeIfAbsent(scope.getRolePermission().getPermission().getCode(),
                    ignored -> new LinkedHashSet<>()).add(scope.getScopeType());
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        grouped.forEach((code, values) -> result.put(code, values.stream().sorted().toList()));
        return result;
    }

    private long incrementVersion(String roleName) {
        PermissionVersion version = versionRepository
                .findForUpdateBySubjectTypeAndSubjectKey("role", roleName)
                .orElseGet(() -> {
                    PermissionVersion created = new PermissionVersion();
                    created.setSubjectType("role");
                    created.setSubjectKey(roleName);
                    created.setVersion(0L);
                    return created;
                });
        version.setVersion(Math.max(0L, version.getVersion()) + 1L);
        return versionRepository.save(version).getVersion();
    }

    private long currentVersion(String roleName) {
        return versionRepository.findBySubjectTypeAndSubjectKey("role", roleName)
                .map(PermissionVersion::getVersion)
                .orElse(1L);
    }

    private Set<String> currentSelectedPermissions(String roleName) {
        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) permissionService.capabilities(roleName).get("permissions");
        Set<String> defined = permissionRepository.findByEnabledTrueOrderByModuleAscCodeAsc().stream()
                .map(PermissionDefinition::getCode)
                .collect(java.util.stream.Collectors.toSet());
        return capabilities.stream()
                .filter(defined::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Object> roleMap(Role role) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", role.getId());
        result.put("name", role.getName());
        result.put("displayName", role.getDisplayName());
        result.put("description", role.getDescription());
        result.put("permissions", new ArrayList<>(currentSelectedPermissions(role.getName())));
        result.put("scopes", currentScopes(role));
        result.put("permissionVersion", currentVersion(role.getName()));
        result.put("isSystem", role.getIsSystem());
        result.put("createdAt", role.getCreatedAt() != null ? role.getCreatedAt().toString() : "");
        return result;
    }

    private Map<String, Object> definitionMap(PermissionDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", definition.getCode());
        result.put("label", definition.getName());
        result.put("group", definition.getModule());
        result.put("description", definition.getDescription());
        result.put("riskLevel", definition.getRiskLevel());
        return result;
    }

    private Map<String, Object> snapshot(Role role, Set<String> permissions,
                                         Map<String, List<String>> scopes, long version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", role.getName());
        result.put("displayName", role.getDisplayName());
        result.put("description", role.getDescription());
        result.put("permissions", permissions);
        result.put("scopes", scopes);
        result.put("permissionVersion", version);
        return result;
    }

    private void writeAudit(Actor actor, String action, String targetKey,
                            Map<String, Object> before, Map<String, Object> after) {
        PermissionAuditLog log = new PermissionAuditLog();
        log.setActorUserId(actor.userId());
        log.setActorName(actor.name());
        log.setAction(action);
        log.setTargetType("role");
        log.setTargetKey(targetKey);
        log.setReason(actor.reason().trim());
        log.setBeforeData(toJson(before));
        log.setAfterData(toJson(after));
        log.setSourceIp(actor.sourceIp());
        auditRepository.save(log);
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法记录权限审计快照", e);
        }
    }

    private String validateRoleName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_]{2,60}")) {
            throw new IllegalArgumentException("角色标识限2-60位英文、数字或下划线");
        }
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    private void validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.trim().length() > 100) {
            throw new IllegalArgumentException("角色名称不能为空且不能超过100字");
        }
    }

    private void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("请填写500字以内的权限变更原因");
        }
    }

    public record Actor(String userId, String name, String reason, String sourceIp) {
    }
}
