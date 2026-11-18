package com.emie.designpm.admin.service;

import com.emie.designpm.admin.repository.PermissionAuditLogRepository;
import com.emie.designpm.admin.repository.PermissionVersionRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.PermissionAuditLog;
import com.emie.designpm.entity.PermissionVersion;
import com.emie.designpm.entity.User;
import com.emie.designpm.reference.repository.DepartmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户项目数据范围覆盖，功能权限仍由角色配置决定。 */
@Service
@Transactional(readOnly = true)
public class UserProjectScopeService {
    private static final Set<String> SCOPES = Set.of("inherit", "own", "department", "role_team");
    private static final Set<String> PERMISSIONS = Set.of("project.view", "project.detail.view", "subtask.view");
    private final UserRepository users;
    private final PermissionVersionRepository versions;
    private final PermissionAuditLogRepository audits;
    private final ObjectMapper objectMapper;
    private DepartmentRepository departments;

    @Autowired(required = false)
    void setDepartmentRepository(DepartmentRepository departments) {
        this.departments = departments;
    }

    public UserProjectScopeService(
            UserRepository users,
            PermissionVersionRepository versions,
            PermissionAuditLogRepository audits,
            ObjectMapper objectMapper) {
        this.users = users;
        this.versions = versions;
        this.audits = audits;
        this.objectMapper = objectMapper;
    }

    public static String configuredScope(User user) {
        String value = user.getProjectDataScope();
        return value == null || !SCOPES.contains(value) ? "inherit" : value;
    }

    public List<String> resolveScopes(String role, String userId, String permission, List<String> inherited) {
        if (!"planner".equals(PermissionCatalog.normalizeRole(role)) || !PERMISSIONS.contains(permission)) {
            return inherited;
        }
        String configured = effectiveOverride(userId);
        return "inherit".equals(configured) ? inherited : List.of(configured);
    }

    public Map<String, List<String>> applyScopes(String role, String userId, Map<String, List<String>> inherited) {
        Map<String, List<String>> effective = new LinkedHashMap<>(inherited);
        if (!"planner".equals(PermissionCatalog.normalizeRole(role))) return effective;
        String configured = effectiveOverride(userId);
        if (!"inherit".equals(configured)) {
            for (String permission : PERMISSIONS) {
                if (effective.containsKey(permission)) effective.put(permission, List.of(configured));
            }
        }
        return effective;
    }

    private String effectiveOverride(String userId) {
        String configured = users.findByUserId(userId)
                .map(UserProjectScopeService::configuredScope)
                .orElse("own");
        if (!"department".equals(configured)) return configured;
        boolean head = departments != null
                && departments
                        .findByHeadUserId(userId)
                        .filter(department -> Boolean.TRUE.equals(department.getActive()))
                        .filter(department -> "planner".equals(PermissionCatalog.normalizeRole(department.getRole())))
                        .isPresent();
        return head ? "department" : "own";
    }

    public long version(String userId) {
        return versions.findBySubjectTypeAndSubjectKey("user", userId)
                .map(PermissionVersion::getVersion)
                .orElse(0L);
    }

    @Transactional
    public Map<String, Object> updateScope(Long id, String scope, PermissionManagementService.Actor actor) {
        if (!SCOPES.contains(scope == null ? "" : scope)) throw new IllegalArgumentException("无效的项目可见范围");
        String reason = actor.reason();
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("请填写500字以内的权限变更原因");
        }
        User found = users.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        User user =
                users.findByUserIdForUpdate(found.getUserId()).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!"planner".equals(PermissionCatalog.normalizeRole(user.getRole()))) {
            throw new IllegalArgumentException("仅产品企划可设置项目可见范围");
        }
        String before = configuredScope(user);
        user.setProjectDataScope("inherit".equals(scope) ? null : scope);
        users.save(user);
        PermissionVersion version = versions.findForUpdateBySubjectTypeAndSubjectKey("user", user.getUserId())
                .orElseGet(() -> {
                    PermissionVersion created = new PermissionVersion();
                    created.setSubjectType("user");
                    created.setSubjectKey(user.getUserId());
                    created.setVersion(0L);
                    return created;
                });
        version.setVersion(version.getVersion() + 1L);
        versions.save(version);
        PermissionAuditLog audit = new PermissionAuditLog();
        audit.setActorUserId(actor.userId());
        audit.setActorName(actor.name());
        audit.setTargetType("user");
        audit.setTargetKey(user.getUserId());
        audit.setAction("user.project_scope.update");
        audit.setReason(reason.trim());
        audit.setBeforeData(snapshot(before));
        audit.setAfterData(snapshot(scope));
        audit.setSourceIp(actor.sourceIp());
        audits.save(audit);
        return Map.of("userId", user.getUserId(), "projectDataScope", scope, "permissionVersion", version.getVersion());
    }

    private String snapshot(String scope) {
        try {
            return objectMapper.writeValueAsString(Map.of("projectDataScope", scope));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法记录项目范围审计", error);
        }
    }
}
