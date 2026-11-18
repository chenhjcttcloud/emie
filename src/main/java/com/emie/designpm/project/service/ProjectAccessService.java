package com.emie.designpm.project.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.admin.service.PermissionCatalog;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.User;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.reference.repository.DepartmentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** 统一解析角色可见的项目范围；企划项目视角统一，部门负责人只有查看权限，不自动获得项目写权限。 */
@Service
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PermissionService permissionService;

    @Autowired
    public ProjectAccessService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            DepartmentRepository departmentRepository,
            PermissionService permissionService) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.permissionService = permissionService;
    }

    /** 保留给轻量单元测试。 */
    public ProjectAccessService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            DepartmentRepository departmentRepository) {
        this(projectRepository, userRepository, departmentRepository, null);
    }

    public List<Project> findVisibleProjectsLight(String viewerRole, String viewerUserId) {
        String role = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(role, "project.view")) return List.of();
        if (hasScope(role, viewerUserId, "project.view", "all")) return projectRepository.findAllLight();
        if ("planner".equals(role)) {
            return projectRepository.findPlannerProjectsLight(
                    scopeUserIds(role, viewerUserId, "project.view"), viewerUserId);
        }
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
    public Page<Project> findVisibleProjectsPage(
            String viewerRole, String viewerUserId, String type, boolean participating, Pageable pageable) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        ProjectListQuery query =
                new ProjectListQuery(type, null, null, null, null, null, null, participating, pageable);
        if (!hasPermission(viewerRole, "project.view")) return Page.empty(pageable);
        String queryRole = hasScope(viewerRole, viewerUserId, "project.view", "all") ? "admin" : viewerRole;
        List<String> ownerIds =
                "admin".equals(queryRole) ? List.of() : scopeUserIds(viewerRole, viewerUserId, "project.view");
        return "planner".equals(queryRole)
                ? projectRepository.findVisiblePage(query, queryRole, ownerIds, viewerUserId)
                : projectRepository.findVisiblePage(query, queryRole, ownerIds);
    }

    public Page<Project> findVisibleProjectsPage(String viewerRole, String viewerUserId, ProjectListQuery query) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(viewerRole, "project.view")) return Page.empty(query.pageable());
        String queryRole = hasScope(viewerRole, viewerUserId, "project.view", "all") ? "admin" : viewerRole;
        List<String> ownerIds =
                "admin".equals(queryRole) ? List.of() : scopeUserIds(viewerRole, viewerUserId, "project.view");
        return "planner".equals(queryRole)
                ? projectRepository.findVisiblePage(query, queryRole, ownerIds, viewerUserId)
                : projectRepository.findVisiblePage(query, queryRole, ownerIds);
    }

    public long countVisibleProjects(String viewerRole, String viewerUserId, String type, boolean participating) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(viewerRole, "project.view")) return 0L;
        ProjectListQuery query =
                new ProjectListQuery(type, null, null, null, null, null, null, participating, Pageable.unpaged());
        String queryRole = hasScope(viewerRole, viewerUserId, "project.view", "all") ? "admin" : viewerRole;
        List<String> userIds =
                "admin".equals(queryRole) ? List.of() : scopeUserIds(viewerRole, viewerUserId, "project.view");
        return "planner".equals(queryRole)
                ? projectRepository.countVisible(query, queryRole, userIds, viewerUserId)
                : projectRepository.countVisible(query, queryRole, userIds);
    }

    public List<Long> findVisibleProjectIds(String viewerRole, String viewerUserId) {
        viewerRole = PermissionCatalog.normalizeRole(viewerRole);
        if (!hasPermission(viewerRole, "project.view")) return List.of();
        ProjectListQuery query =
                new ProjectListQuery(null, null, null, null, null, null, null, false, Pageable.unpaged());
        String queryRole = hasScope(viewerRole, viewerUserId, "project.view", "all") ? "admin" : viewerRole;
        List<String> userIds =
                "admin".equals(queryRole) ? List.of() : scopeUserIds(viewerRole, viewerUserId, "project.view");
        return "planner".equals(queryRole)
                ? projectRepository.findVisibleIds(query, queryRole, userIds, viewerUserId)
                : projectRepository.findVisibleIds(query, queryRole, userIds);
    }

    public List<Long> findReviewableProjectIds(String role, String userId) {
        if (!hasPermission(role, "project.view")) return List.of();
        return "planner".equals(PermissionCatalog.normalizeRole(role))
                ? projectRepository.findOwnedPlannerProjectIds(userId)
                : findVisibleProjectIds(role, userId);
    }

    public List<Project> findVisibleProjectsWithTasks(AuthSession session) {
        if (session == null) return List.of();
        String role = PermissionCatalog.normalizeRole(session.role());
        if (!hasPermission(role, "project.view")) return List.of();
        if (hasScope(role, session.userId(), "project.view", "all")) return projectRepository.findAllWithTasks();
        if ("planner".equals(role)) {
            return projectRepository.findPlannerProjectsWithTasks(
                    scopeUserIds(role, session.userId(), "project.view"), session.userId());
        }
        return distinctProjects(scopeUserIds(role, session.userId(), "project.view").stream()
                .flatMap(userId -> findForUserWithTasks(role, userId).stream())
                .toList());
    }

    public boolean canView(Project project, AuthSession session) {
        if (project == null || session == null) return false;
        // 素材广场立项的项目对素材广场可见角色开放详情查看；编辑权限仍由各操作接口单独校验。
        String role = PermissionCatalog.normalizeRole(session.role());
        if ("素材广场".equals(project.getSource()) && !"planner".equals(role)) return true;
        if (!hasPermission(role, "project.detail.view")) return false;
        if (hasScope(role, session.userId(), "project.detail.view", "all")) return true;
        if ("planner".equals(role) && projectRepository.isCollaborator(project.getId(), session.userId())) return true;
        return scopeUserIds(role, session.userId(), "project.detail.view").stream()
                .anyMatch(userId -> canViewAs(project, userId, role));
    }

    /** 返回状态看板允许展示的用户；普通成员只有自己，部门负责人包含本部门同角色成员。 */
    public List<User> visibleUsers(String viewerRole, String viewerUserId, String requestedRole) {
        if ("admin".equals(viewerRole))
            return userRepository.findByRole(requestedRole).stream()
                    .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                    .toList();
        // 项目协作只增加协作项目本身；状态详情仍按可见项目逐项过滤。
        if ("planner".equals(viewerRole) && "planner".equals(requestedRole)) {
            List<String> ids = new ArrayList<>(scopeUserIds(viewerRole, viewerUserId, "project.view"));
            ids.addAll(projectRepository.findCollaboratorProjectPlannerIds(viewerUserId));
            return userRepository.findByUserIdIn(ids).stream()
                    .filter(this::isActive)
                    .toList();
        }
        // 产品企划需要查看执行团队状态面板；这里仅开放状态看板读取，不改变项目编辑权限。
        if ("planner".equals(viewerRole)
                && ("promotion".equals(requestedRole)
                        || "designer".equals(requestedRole)
                        || "supplychain".equals(requestedRole))) {
            return userRepository.findByRole(requestedRole).stream()
                    .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                    .toList();
        }
        if (!viewerRole.equals(requestedRole)) return List.of();

        Map<String, User> users = new LinkedHashMap<>();
        userRepository
                .findByUserId(viewerUserId)
                .filter(user -> requestedRole.equals(user.getRole()))
                .ifPresent(user -> users.put(user.getUserId(), user));

        departmentRepository
                .findByHeadUserId(viewerUserId)
                .filter(department -> Boolean.TRUE.equals(department.getActive()))
                .filter(department -> requestedRole.equals(department.getRole()))
                .ifPresent(department -> userRepository
                        .findByDepartmentIdAndRole(department.getId(), requestedRole)
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

    /** 部门和团队任务只在对应数据范围内展开；仅本人范围不会回退到部门。 */
    public List<String> departmentTaskUserIds(String viewerRole, String viewerUserId) {
        if (!hasPermission(viewerRole, "subtask.view")) return List.of();
        if (hasScope(viewerRole, viewerUserId, "subtask.view", "all")) {
            return userRepository.findAll().stream().map(User::getUserId).toList();
        }
        List<String> scopes = scopes(viewerRole, viewerUserId, "subtask.view");
        if (!scopes.contains("department") && !scopes.contains("role_team")) return List.of();
        return scopeUserIds(viewerRole, viewerUserId, "subtask.view").stream()
                .filter(id -> !id.equals(viewerUserId))
                .toList();
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
        String normalizedRole = PermissionCatalog.normalizeRole(role);
        List<String> scopes = scopes(normalizedRole, userId, permission);
        if (scopes.contains("role_team") || scopes.contains("all")) {
            return userRepository.findByRole(normalizedRole).stream()
                    .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                    .map(User::getUserId)
                    .toList();
        }
        if (scopes.contains("department")) {
            List<String> departmentIds = departmentRepository
                    .findByHeadUserId(userId)
                    .filter(department -> Boolean.TRUE.equals(department.getActive()))
                    .filter(department -> normalizedRole.equals(PermissionCatalog.normalizeRole(department.getRole())))
                    .map(department ->
                            userRepository.findByDepartmentIdAndRole(department.getId(), normalizedRole).stream()
                                    .filter(this::isActive)
                                    .map(User::getUserId)
                                    .toList())
                    .orElse(List.of());
            if (!departmentIds.isEmpty()) {
                java.util.LinkedHashSet<String> ownAndDepartment = new java.util.LinkedHashSet<>();
                if (userId != null && !userId.isBlank()) ownAndDepartment.add(userId);
                ownAndDepartment.addAll(departmentIds);
                return new ArrayList<>(ownAndDepartment);
            }
        }
        return userId == null || userId.isBlank() ? List.of() : List.of(userId);
    }

    private boolean hasScope(String role, String userId, String permission, String expected) {
        return scopes(role, userId, permission).contains(expected);
    }

    private boolean hasPermission(String role, String permission) {
        return permissionService == null || permissionService.has(role, permission);
    }

    private List<String> scopes(String role, String userId, String permission) {
        return permissionService == null
                ? PermissionCatalog.compatibilityScopes(role, permission).stream()
                        .toList()
                : permissionService.scopes(role, userId, permission);
    }

    private boolean isActive(User user) {
        return user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus());
    }

    private boolean canViewAs(Project project, String userId, String role) {
        if ("sales".equals(role)) return java.util.Objects.equals(userId, project.getSalesId());
        if ("planner".equals(role)) {
            return java.util.Objects.equals(userId, project.getPlannerId())
                    || ("channel_custom".equals(project.getType())
                            && "pending_planner".equals(project.getStatus())
                            && (project.getPlannerId() == null
                                    || project.getPlannerId().isBlank()));
        }
        return project.getTasks().stream()
                .anyMatch(task -> java.util.Objects.equals(userId, task.getDesignerId())
                        && (java.util.Objects.equals(role, task.getAssigneeRole())
                                || ("designer".equals(role)
                                        && (task.getAssigneeRole() == null
                                                || task.getAssigneeRole().isBlank()))));
    }

    private List<Project> distinctProjects(List<Project> projects) {
        Map<Long, Project> distinct = new LinkedHashMap<>();
        for (Project project : projects) {
            if (project != null && project.getId() != null) distinct.putIfAbsent(project.getId(), project);
        }
        return new ArrayList<>(distinct.values());
    }
}
