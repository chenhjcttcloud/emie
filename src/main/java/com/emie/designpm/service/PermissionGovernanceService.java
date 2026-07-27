package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.PermissionAuditLog;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.Role;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.PermissionAuditLogRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.RoleRepository;
import com.emie.designpm.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PermissionGovernanceService {
    private final PermissionService permissionService;
    private final PermissionManagementService managementService;
    private final PermissionAuditLogRepository auditRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;

    public PermissionGovernanceService(PermissionService permissionService,
                                       PermissionManagementService managementService,
                                       PermissionAuditLogRepository auditRepository,
                                       RoleRepository roleRepository,
                                       UserRepository userRepository,
                                       ProjectRepository projectRepository,
                                       ProjectAccessService projectAccessService) {
        this.permissionService = permissionService;
        this.managementService = managementService;
        this.auditRepository = auditRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewUser(String userId) {
        User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        Map<String, Object> result = new LinkedHashMap<>(permissionService.capabilities(user.getRole()));
        result.put("userId", user.getUserId());
        result.put("userName", user.getName());
        result.put("role", PermissionCatalog.normalizeRole(user.getRole()));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> simulate(String userId, String permission, Long projectId) {
        User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        boolean functional = permissionService.has(user.getRole(), permission);
        List<String> scopes = permissionService.scopes(user.getRole(), permission);
        boolean inScope = true;
        String scopeReason = projectId == null ? "未指定项目，仅校验功能权限" : "无需项目范围";
        if (projectId != null) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
            inScope = projectAccessService.canView(project,
                    new AuthController.AuthSession(user.getUserId(), user.getRole(), user.getName()));
            scopeReason = inScope ? "项目位于当前用户可见范围" : "项目不在当前用户可见范围";
        }
        boolean allowed = functional && inScope;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", allowed);
        result.put("userId", userId);
        result.put("role", PermissionCatalog.normalizeRole(user.getRole()));
        result.put("permission", permission);
        result.put("functionalPermission", functional);
        result.put("scopes", scopes);
        result.put("projectId", projectId);
        result.put("scopeMatched", inScope);
        result.put("reasonChain", List.of(
                functional ? "角色已授予该功能权限" : "角色未授予或明确禁止该功能权限",
                scopeReason,
                allowed ? "允许：仍需继续满足接口自身的业务状态条件" : "拒绝"));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String roleName) {
        List<PermissionAuditLog> logs = roleName == null || roleName.isBlank()
                ? auditRepository.findTop100ByTargetTypeOrderByCreatedAtDesc("role")
                : auditRepository.findTop50ByTargetTypeAndTargetKeyOrderByCreatedAtDesc("role", roleName);
        return logs.stream().map(this::auditMap).toList();
    }

    public Map<String, Object> rollback(Long roleId, Long auditId, PermissionManagementService.Actor actor) {
        return managementService.rollbackRole(roleId, auditId, actor);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> anomalies() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Role role : roleRepository.findAll()) {
            int users = userRepository.findByRole(role.getName()).size();
            @SuppressWarnings("unchecked")
            List<String> permissions = (List<String>) permissionService.capabilities(role.getName()).get("permissions");
            if (users == 0) result.add(anomaly("unused_role", "warning", role, "该角色当前没有用户"));
            if (!Boolean.TRUE.equals(role.getIsSystem()) && permissions.size() >= 30) {
                result.add(anomaly("excessive_permissions", "high", role,
                        "自定义角色拥有 " + permissions.size() + " 项权限，请复核最小权限原则"));
            }
            for (String code : List.of("project.view", "project.detail.view", "subtask.view")) {
                if (permissions.contains(code) && permissionService.scopes(role.getName(), code).isEmpty()) {
                    result.add(anomaly("missing_scope", "high", role, code + " 缺少数据范围"));
                }
            }
        }
        return result;
    }

    private Map<String, Object> auditMap(PermissionAuditLog log) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", log.getId());
        result.put("action", log.getAction());
        result.put("targetKey", log.getTargetKey());
        result.put("actorName", log.getActorName());
        result.put("reason", log.getReason());
        result.put("beforeData", log.getBeforeData());
        result.put("afterData", log.getAfterData());
        result.put("createdAt", log.getCreatedAt());
        return result;
    }

    private Map<String, Object> anomaly(String type, String severity, Role role, String message) {
        return Map.of("type", type, "severity", severity, "roleId", role.getId(),
                "roleName", role.getName(), "displayName", role.getDisplayName(), "message", message);
    }
}
