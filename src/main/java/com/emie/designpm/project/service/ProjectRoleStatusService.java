package com.emie.designpm.project.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import java.util.*;
import java.util.stream.Collectors;

/** 状态面板组装，按查看者的项目范围裁剪项目和执行任务。 */
final class ProjectRoleStatusService {
    private final ProjectAccessService projectAccessService;
    private final SubTaskRepository subTaskRepository;
    private final ProjectRepository projectRepository;

    ProjectRoleStatusService(
            ProjectAccessService projectAccessService,
            SubTaskRepository subTaskRepository,
            ProjectRepository projectRepository) {
        this.projectAccessService = projectAccessService;
        this.subTaskRepository = subTaskRepository;
        this.projectRepository = projectRepository;
    }

    Map<String, Object> getRoleStatus(String role, String viewerRole, String viewerUserId, String scope) {
        List<User> users = projectAccessService.visibleUsers(viewerRole, viewerUserId, role);
        if ("planner".equals(viewerRole) && "planner".equals(role) && "mine".equalsIgnoreCase(scope)) {
            users = users.stream()
                    .filter(u -> Objects.equals(u.getUserId(), viewerUserId))
                    .toList();
        }
        Set<Long> visibleIds = "planner".equals(viewerRole)
                ? new HashSet<>(projectAccessService.findVisibleProjectIds(viewerRole, viewerUserId))
                : null;
        Map<String, Object> result = new LinkedHashMap<>();

        if ("designer".equals(role) || "supplychain".equals(role) || "promotion".equals(role)) {
            // 批量查询所有用户的子任务（一次 SQL 替代 N 次）
            List<String> userIds = users.stream().map(User::getUserId).collect(Collectors.toList());
            List<SubTask> allTasks = userIds.isEmpty() ? List.of() : subTaskRepository.findByDesignerIds(userIds);
            Map<String, List<SubTask>> tasksByUser = allTasks.stream()
                    .filter(task -> visibleIds == null
                            || visibleIds.contains(task.getProject().getId()))
                    .collect(Collectors.groupingBy(SubTask::getDesignerId));

            for (User u : users) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", u.getUserId());
                info.put("name", u.getName());
                info.put("title", u.getTitle());

                List<SubTask> userTasks = tasksByUser.getOrDefault(u.getUserId(), List.of());
                // pending 代表尚未接单，不应把设计师标记为进行中/忙碌。
                // 状态看板只展示负责人仍需执行的任务；已交付/送审中交由企划审核，不再算执行中。
                List<SubTask> activeTasks = userTasks.stream()
                        .filter(t -> List.of("accepted", "rejected", "revision_requested")
                                .contains(t.getStatus()))
                        .collect(Collectors.toList());
                List<SubTask> completedTasks = userTasks.stream()
                        .filter(t -> List.of("approved", "completed", "sales_approved", "admin_approved")
                                .contains(t.getStatus()))
                        .collect(Collectors.toList());

                info.put(
                        "activeTasks",
                        activeTasks.stream()
                                .map(t -> {
                                    Map<String, Object> tm = new LinkedHashMap<>();
                                    tm.put("id", t.getId());
                                    tm.put("name", t.getName());
                                    tm.put("status", t.getStatus());
                                    tm.put("projectId", t.getProject().getId());
                                    return tm;
                                })
                                .collect(Collectors.toList()));
                info.put("completedTasks", completedTasks.size());
                info.put("busy", !activeTasks.isEmpty());
                info.put("label", "designer".equals(role) ? "设计师" : ("promotion".equals(role) ? "产品推广" : "供应链"));
                result.put(u.getUserId(), info);
            }
        } else if ("sales".equals(role) || "planner".equals(role)) {
            List<String> userIds = users.stream().map(User::getUserId).toList();
            List<Project> allRoleProjects = userIds.isEmpty()
                    ? List.of()
                    : ("sales".equals(role)
                            ? projectRepository.findBySalesIdsLight(userIds)
                            : projectRepository.findByPlannerIdsLight(userIds));
            allRoleProjects = allRoleProjects.stream()
                    .filter(project -> visibleIds == null || visibleIds.contains(project.getId()))
                    .toList();
            Map<String, List<Project>> projectsByUser = allRoleProjects.stream()
                    .collect(Collectors.groupingBy(
                            p -> "sales".equals(role)
                                    ? p.getSalesId()
                                    : (p.getPlannerId() == null ? "" : p.getPlannerId()),
                            LinkedHashMap::new,
                            Collectors.toList()));
            List<Project> unassignedPlannerProjects = "planner".equals(role)
                    ? allRoleProjects.stream()
                            .filter(p ->
                                    p.getPlannerId() == null || p.getPlannerId().isBlank())
                            .toList()
                    : List.of();
            for (User u : users) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", u.getUserId());
                info.put("name", u.getName());
                info.put("title", u.getTitle());

                List<Project> projects = new ArrayList<>(projectsByUser.getOrDefault(u.getUserId(), List.of()));
                if ("planner".equals(role)) projects.addAll(unassignedPlannerProjects);
                List<Project> activeProjects = projects.stream()
                        .filter(p ->
                                !List.of("draft", "terminated", "completed").contains(p.getStatus()))
                        .collect(Collectors.toList());

                info.put(
                        "activeProjects",
                        activeProjects.stream()
                                .map(p -> {
                                    Map<String, Object> pm = new LinkedHashMap<>();
                                    pm.put("id", p.getId());
                                    pm.put("projectCode", p.getProjectCode());
                                    pm.put("productName", p.getProductName());
                                    pm.put("status", p.getStatus());
                                    pm.put("type", p.getType());
                                    return pm;
                                })
                                .collect(Collectors.toList()));
                if ("planner".equals(role)) {
                    List<Map<String, Object>> activeTasks = activeProjects.stream()
                            .flatMap(p -> p.getTasks().stream()
                                    .filter(t -> List.of("pending", "accepted", "rejected", "revision_requested")
                                            .contains(t.getStatus()))
                                    .map(t -> {
                                        Map<String, Object> tm = new LinkedHashMap<>();
                                        tm.put("id", t.getId());
                                        tm.put("name", t.getName());
                                        tm.put("status", t.getStatus());
                                        tm.put("projectId", p.getId());
                                        return tm;
                                    }))
                            .toList();
                    info.put("activeTasks", activeTasks);
                }
                info.put(
                        "completedProjects",
                        projects.stream()
                                .filter(p -> "completed".equals(p.getStatus()))
                                .count());
                info.put("busy", !activeProjects.isEmpty());
                info.put("label", "sales".equals(role) ? "销售" : "产品企划");
                result.put(u.getUserId(), info);
            }
        }
        return result;
    }
}
