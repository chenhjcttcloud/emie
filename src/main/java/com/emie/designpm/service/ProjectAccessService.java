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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统一解析本人及部门负责人可见的项目范围。部门负责人只有查看权限，不自动获得项目写权限。 */
@Service
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    public ProjectAccessService(ProjectRepository projectRepository,
                                UserRepository userRepository,
                                DepartmentRepository departmentRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
    }

    public List<Project> findVisibleProjectsLight(String viewerRole, String viewerUserId) {
        if ("admin".equals(viewerRole)) return projectRepository.findAllLight();
        return distinctProjects(visibleUserIds(viewerRole, viewerUserId, viewerRole).stream()
                .flatMap(userId -> findForUserLight(viewerRole, userId).stream())
                .toList());
    }

    public List<Project> findParticipatingProjectsLight(String viewerRole, String viewerUserId) {
        if (!"designer".equals(viewerRole) && !"supplychain".equals(viewerRole)) return List.of();
        return distinctProjects(visibleUserIds(viewerRole, viewerUserId, viewerRole).stream()
                .flatMap(userId -> projectRepository.findParticipatingByAssigneeLight(userId, viewerRole).stream())
                .toList());
    }

    /** 项目列表分页：将角色可见范围和数据库分页合并，避免默认读取整张项目表。 */
    public Page<Project> findVisibleProjectsPage(String viewerRole, String viewerUserId, String type,
                                                  boolean participating, Pageable pageable) {
        ProjectListQuery query = new ProjectListQuery(type, null, null, null, null, null, null, participating, pageable);
        if ("admin".equals(viewerRole)) return projectRepository.findVisiblePage(query, viewerRole, List.of());
        List<String> userIds = visibleUserIds(viewerRole, viewerUserId, viewerRole);
        return projectRepository.findVisiblePage(query, viewerRole, userIds);
    }

    public Page<Project> findVisibleProjectsPage(String viewerRole, String viewerUserId, ProjectListQuery query) {
        if ("admin".equals(viewerRole)) return projectRepository.findVisiblePage(query, viewerRole, List.of());
        return projectRepository.findVisiblePage(query, viewerRole, visibleUserIds(viewerRole, viewerUserId, viewerRole));
    }

    public List<Project> findVisibleProjectsWithTasks(AuthController.AuthSession session) {
        if (session == null) return List.of();
        if ("admin".equals(session.role())) return projectRepository.findAllWithTasks();
        return distinctProjects(visibleUserIds(session.role(), session.userId(), session.role()).stream()
                .flatMap(userId -> findForUserWithTasks(session.role(), userId).stream())
                .toList());
    }

    public boolean canView(Project project, AuthController.AuthSession session) {
        if (ProjectAccessPolicy.canView(project, session)) return true;
        if (project == null || session == null || "admin".equals(session.role())) return false;

        return visibleUsers(session.role(), session.userId(), session.role()).stream()
                .filter(user -> !session.userId().equals(user.getUserId()))
                .anyMatch(user -> ProjectAccessPolicy.canView(project,
                        new AuthController.AuthSession(user.getUserId(), user.getRole(), user.getName())));
    }

    /** 返回状态看板允许展示的用户；普通成员只有自己，部门负责人包含本部门同角色成员。 */
    public List<User> visibleUsers(String viewerRole, String viewerUserId, String requestedRole) {
        if ("admin".equals(viewerRole)) return userRepository.findByRole(requestedRole);
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

    private List<Project> findForUserLight(String role, String userId) {
        return switch (role) {
            case "sales" -> projectRepository.findBySalesIdLight(userId);
            case "planner" -> projectRepository.findByPlannerViewLight(userId);
            case "designer", "supplychain" -> projectRepository.findByAssigneeViewLight(userId, role);
            default -> List.of();
        };
    }

    private List<Project> findForUserWithTasks(String role, String userId) {
        return switch (role) {
            case "sales" -> projectRepository.findBySalesId(userId);
            case "planner" -> projectRepository.findByPlannerView(userId);
            case "designer", "supplychain" -> projectRepository.findByAssigneeView(userId, role);
            default -> List.of();
        };
    }

    private List<Project> distinctProjects(List<Project> projects) {
        Map<Long, Project> distinct = new LinkedHashMap<>();
        for (Project project : projects) {
            if (project != null && project.getId() != null) distinct.putIfAbsent(project.getId(), project);
        }
        return new ArrayList<>(distinct.values());
    }
}
