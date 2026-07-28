package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.Department;
import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.util.ProjectAccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统一解析角色可见的项目范围；企划项目视角统一，部门负责人只有查看权限，不自动获得项目写权限。 */
@Service
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PermissionService permissionService;

    @Autowired
    public ProjectAccessService(ProjectRepository projectRepository,
                                UserRepository userRepository,
                                DepartmentRepository departmentRepository,
                                PermissionService permissionService) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.permissionService = permissionService;
    }

    /** 保留给轻量单元测试。 */
    public ProjectAccessService(ProjectRepository projectRepository,
                                UserRepository userRepository,
                                DepartmentRepository departmentRepository) {
        this(projectRepository, userRepository, departmentRepository, null);
    }

    public List<Project> findVisibleProjectsLight(String viewerRole, String viewerUserId) {
        String role = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(role, "project.view")) return List.of();
        if (hasScope(role, "project.view", "all")) return projectRepository.findAllLight();
        return distinctProjects(scopeUserIds(role, viewerUserId, "project.view").stream()
                .flatMap(userId -> findForUserLight(role, userId).stream())
                .toList());
    }

    public List<Project> findParticipatingProjectsLight(String viewerRole, String viewerUserId) {
        String role = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(role, "project.view")) return List.of();
        if (!List.of("designer", "supplychain", "promotion").contains(role)) return List.of();
        return distinctProjects(scopeUserIds(role, viewerUserId, "project.view").stream()
                .flatMap(userId -> projectRepository.findParticipatingByAssigneeLight(userId, role).stream())
                .toList());
    }

    /** 项目列表分页：将角色可见范围和数据库分页合并，避免默认读取整张项目表。 */
    public Page<Project> findVisibleProjectsPage(String viewerRole, String viewerUserId, String type,
                                                  boolean participating, Pageable pageable) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        ProjectListQuery query = new ProjectListQuery(type, null, null, null, null, null, null, participating, pageable);
        if (!hasPermission(viewerRole, "project.view")) return Page.empty(pageable);
        String queryRole = hasScope(viewerRole, "project.view", "all") ? "admin" : viewerRole;
        return projectRepository.findVisiblePage(query, queryRole,
                "admin".equals(queryRole) ? List.of() : scopeUserIds(viewerRole, viewerUserId, "project.view"));
    }

    public Page<Project> findVisibleProjectsPage(String viewerRole, String viewerUserId, ProjectListQuery query) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(viewerRole, "project.view")) return Page.empty(query.pageable());
        String queryRole = hasScope(viewerRole, "project.view", "all") ? "admin" : viewerRole;
        return projectRepository.findVisiblePage(query, queryRole,
                "admin".equals(queryRole) ? List.of() : scopeUserIds(viewerRole, viewerUserId, "project.view"));
    }

    public long countVisibleProjects(String viewerRole, String viewerUserId, String type, boolean participating) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(viewerRole, "project.view")) return 0L;
        ProjectListQuery query = new ProjectListQuery(type, null, null, null, null, null, null, participating,
                Pageable.unpaged());
        String queryRole = hasScope(viewerRole, "project.view", "all") ? "admin" : viewerRole;
        List<String> userIds = "admin".equals(queryRole) ? List.of()
                : scopeUserIds(viewerRole, viewerUserId, "project.view");
        return projectRepository.countVisible(query, queryRole, userIds);
    }

    public List<Long> findVisibleProjectIds(String viewerRole, String viewerUserId) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(viewerRole, "project.view")) return List.of();
        ProjectListQuery query = new ProjectListQuery(null, null, null, null, null, null, null, false, Pageable.unpaged());
        String queryRole = hasScope(viewerRole, "project.view", "all") ? "admin" : viewerRole;
        List<String> userIds = "admin".equals(queryRole) ? List.of()
                : scopeUserIds(viewerRole, viewerUserId, "project.view");
        return projectRepository.findVisibleIds(query, queryRole, userIds);
    }

    public List<Project> findVisibleProjectsWithTasks(AuthController.AuthSession session) {
        if (session == null) return List.of();
        String role = PermissionCatalog.normalizeRole(session.role());
        if (!hasPermission(role, "project.view")) return List.of();
        if (hasScope(role, "project.view", "all")) return projectRepository.findAllWithTasks();
        return distinctProjects(scopeUserIds(role, session.userId(), "project.view").stream()
                .flatMap(userId -> findForUserWithTasks(role, userId).stream())
                .toList());
    }

    public boolean canView(Project project, AuthController.AuthSession session) {
        if (project == null || session == null) return false;
        String role = PermissionCatalog.normalizeRole(session.role());
        if (!hasPermission(role, "project.detail.view")) return false;
        if (hasScope(role, "project.detail.view", "all")) return true;
        return scopeUserIds(role, session.userId(), "project.detail.view").stream()
                .anyMatch(userId -> canViewAs(project, userId, role));
    }

    /** 返回状态看板允许展示的用户；普通成员只有自己，部门负责人包含本部门同角色成员。 */
    public List<User> visibleUsers(String viewerRole, String viewerUserId, String requestedRole) {
        if ("admin".equals(viewerRole)) return userRepository.findByRole(requestedRole);
        // 所有产品企划使用统一项目视角，不再按部门负责人或所属部门切分项目范围。
        // 写入/编辑权限仍由 ProjectService 按项目负责人单独校验。
        if ("planner".equals(viewerRole) && "planner".equals(requestedRole)) {
            return userRepository.findByRole("planner").stream()
                    .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                    .toList();
        }
        // 产品企划需要查看执行团队状态面板；这里仅开放状态看板读取，不改变项目编辑权限。
        if ("planner".equals(viewerRole) && ("designer".equals(requestedRole) || "supplychain".equals(requestedRole))) {
            return userRepository.findByRole(requestedRole).stream()
                    .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                    .toList();
        }
        if (!viewerRole.equals(requestedRole)) return List.of();

        Map<String, User> users = new LinkedHashMap<>();
        userRepository.findByUserId(viewerUserId)
                .filter(user -> requestedRole.equals(user.getRole()))
                .ifPresent(user -> users.put(user.getUserId(), user));

        departmentRepository.findByHeadUserId(viewerUserId)
                .filter(department -> Boolean.TRUE.equals(department.getActive()))
                .filter(department -> requestedRole.equals(department.getRole()))
                .ifPresent(department -> userRepository.findByDepartmentIdAndRole(department.getId(), requestedRole)
                        .forEach(user -> users.put(user.getUserId(), user)));
        return new ArrayList<>(users.values());
    }

    public List<String> visibleUserIds(String viewerRole, String viewerUserId, String requestedRole) {
        List<String> ids = visibleUsers(viewerRole, viewerUserId, requestedRole).stream()
                .map(User::getUserId)
                .toList();
        if (ids.isEmpty() && viewerRole.equals(requestedRole) && viewerUserId != null && !viewerUserId.isBlank()) {
            return List.of(viewerUserId);
        }
        return ids;
    }

    /** 部门负责人可查看本部门成员关联的任务；系统管理员可查看全部。 */
    public List<String> departmentTaskUserIds(String viewerRole, String viewerUserId) {
        if (!hasPermission(viewerRole, "subtask.view")) return List.of();
        if (hasScope(viewerRole, "subtask.view", "all")) {
            return userRepository.findAll().stream().map(User::getUserId).toList();
        }
        List<String> scoped = scopeUserIds(viewerRole, viewerUserId, "subtask.view");
        if (!scoped.isEmpty() && (hasScope(viewerRole, "subtask.view", "department")
                || hasScope(viewerRole, "subtask.view", "role_team"))) {
            return scoped.stream().filter(id -> !id.equals(viewerUserId)).toList();
        }
        if ("planner".equals(viewerRole)) {
            return userRepository.findByUserId(viewerUserId)
                    .map(User::getDepartmentId)
                    .map(userRepository::findByDepartmentId)
                    .orElseGet(() -> userRepository.findByRole("planner"))
                    .stream()
                    .filter(user -> "planner".equals(user.getRole()))
                    .map(User::getUserId)
                    .filter(id -> !id.equals(viewerUserId))
                    .toList();
        }
        return departmentRepository.findByHeadUserId(viewerUserId)
                .filter(d -> Boolean.TRUE.equals(d.getActive())
                        && ("sales".equals(d.getRole()) || "planner".equals(d.getRole())))
                .map(d -> userRepository.findByDepartmentId(d.getId()).stream()
                        .map(User::getUserId)
                        .filter(id -> !id.equals(viewerUserId))
                        .toList())
                .orElse(List.of());
    }

    private List<Project> findForUserLight(String role, String userId) {
        role = PermissionCatalog.normalizeRole(role);
        return switch (role) {
            case "sales" -> projectRepository.findBySalesIdLight(userId);
            case "planner" -> projectRepository.findByPlannerViewLight(userId);
            default -> projectRepository.findByAssigneeViewLight(userId, role);
        };
    }

    private List<Project> findForUserWithTasks(String role, String userId) {
        role = PermissionCatalog.normalizeRole(role);
        return switch (role) {
            case "sales" -> projectRepository.findBySalesId(userId);
            case "planner" -> projectRepository.findByPlannerView(userId);
            default -> projectRepository.findByAssigneeView(userId, role);
        };
    }

    private List<String> scopeUserIds(String role, String userId, String permission) {
        role = PermissionCatalog.normalizeRole(role);
        List<String> scopes = scopes(role, permission);
        if (scopes.contains("role_team")) {
            return userRepository.findByRole(role).stream()
                    .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                    .map(User::getUserId).toList();
        }
        if (scopes.contains("department")) return visibleUserIds(role, userId, role);
        return userId == null || userId.isBlank() ? List.of() : List.of(userId);
    }

    private boolean hasScope(String role, String permission, String expected) {
        return scopes(role, permission).contains(expected);
    }

    private boolean hasPermission(String role, String permission) {
        return permissionService == null || permissionService.has(role, permission);
    }

    private List<String> scopes(String role, String permission) {
        return permissionService == null
                ? PermissionCatalog.compatibilityScopes(role, permission).stream().toList()
                : permissionService.scopes(role, permission);
    }

    private boolean canViewAs(Project project, String userId, String role) {
        if ("sales".equals(role)) return java.util.Objects.equals(userId, project.getSalesId());
        if ("planner".equals(role)) {
            return java.util.Objects.equals(userId, project.getPlannerId())
                    || ("channel_custom".equals(project.getType())
                    && "pending_planner".equals(project.getStatus())
                    && (project.getPlannerId() == null || project.getPlannerId().isBlank()));
        }
        return project.getTasks().stream().anyMatch(task ->
                java.util.Objects.equals(userId, task.getDesignerId())
                        && (java.util.Objects.equals(role, task.getAssigneeRole())
                        || ("designer".equals(role)
                        && (task.getAssigneeRole() == null || task.getAssigneeRole().isBlank()))));
    }

    private List<Project> distinctProjects(List<Project> projects) {
        Map<Long, Project> distinct = new LinkedHashMap<>();
        for (Project project : projects) {
            if (project != null && project.getId() != null) distinct.putIfAbsent(project.getId(), project);
        }
        return new ArrayList<>(distinct.values());
    }
}
