package com.emie.designpm.project.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.admin.service.PermissionCatalog;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ProjectCollaborator;
import com.emie.designpm.entity.User;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectCollaborationService {
    private final ProjectRepository projects;
    private final ProjectCollaboratorRepository collaborators;
    private final UserRepository users;
    private final ProjectNotifier notifier;

    public ProjectCollaborationService(
            ProjectRepository projects,
            ProjectCollaboratorRepository collaborators,
            UserRepository users,
            NotificationWorkflowService notifications) {
        this.projects = projects;
        this.collaborators = collaborators;
        this.users = users;
        this.notifier = new ProjectNotifier(notifications);
    }

    @Transactional
    public Project replaceCollaborators(Long projectId, List<String> requested, AuthSession actor) {
        Project project =
                projects.findByIdForUpdate(projectId).orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        requireOwner(project, actor);
        if (requested == null || requested.size() > 20) throw new IllegalArgumentException("请选择最多 20 位协作企划");
        if (requested.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("协作企划账号不能为空");
        }
        Set<String> selected = requested.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, User> selectedUsers =
                users.findByUserIdIn(selected).stream().collect(Collectors.toMap(User::getUserId, user -> user));
        for (String userId : selected) {
            User user = selectedUsers.get(userId);
            if (user == null
                    || !"planner".equals(PermissionCatalog.normalizeRole(user.getRole()))
                    || (user.getStatus() != null && !"active".equalsIgnoreCase(user.getStatus()))) {
                throw new IllegalArgumentException("协作企划必须为在职产品企划");
            }
            if (Objects.equals(userId, project.getPlannerId())) throw new IllegalArgumentException("项目负责人无需重复添加为协作企划");
        }
        List<ProjectCollaborator> previous = collaborators.findByProjectId(projectId);
        Set<String> oldIds = previous.stream()
                .map(ProjectCollaborator::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (oldIds.equals(selected)) return project;
        collaborators.deleteAll(previous.stream()
                .filter(item -> !selected.contains(item.getUserId()))
                .toList());
        List<String> added =
                selected.stream().filter(userId -> !oldIds.contains(userId)).toList();
        for (String userId : added) {
            ProjectCollaborator membership = new ProjectCollaborator();
            membership.setProjectId(projectId);
            membership.setUserId(userId);
            membership.setAssignedBy(actor.userId());
            membership.setCreatedAt(LocalDateTime.now());
            collaborators.save(membership);
        }
        project.getLogs()
                .add(new ActivityLog(
                        "调整项目协作企划",
                        actor.name(),
                        actor.role(),
                        project,
                        "project",
                        projectId,
                        AuditJson.toJson(Map.of("collaboratorIds", oldIds)),
                        AuditJson.toJson(Map.of("collaboratorIds", selected)),
                        "collaboratorIds"));
        project.setUpdatedAt(LocalDateTime.now());
        projects.saveAndFlush(project);
        for (String userId : added) {
            notifier.safeNotifyAfterCommit(
                    "PROJECT_COLLABORATION_ASSIGNED",
                    userId,
                    "project",
                    projectId,
                    actor.userId(),
                    notifier.context(project, null, actor.name(), ""));
        }
        return project;
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> collaborators(Long projectId) {
        if (projectId == null) return List.of();
        List<String> ids = collaborators.findByProjectId(projectId).stream()
                .map(ProjectCollaborator::getUserId)
                .sorted()
                .toList();
        Map<String, String> names =
                users.findByUserIdIn(ids).stream().collect(Collectors.toMap(User::getUserId, User::getName));
        return ids.stream()
                .map(userId -> {
                    Map<String, String> member = new LinkedHashMap<>();
                    member.put("userId", userId);
                    member.put("name", names.getOrDefault(userId, userId));
                    return member;
                })
                .toList();
    }

    private void requireOwner(Project project, AuthSession actor) {
        if (actor == null
                || actor.userId() == null
                || actor.userId().isBlank()
                || !("admin".equals(PermissionCatalog.normalizeRole(actor.role()))
                        || ("planner".equals(PermissionCatalog.normalizeRole(actor.role()))
                                && Objects.equals(actor.userId(), project.getPlannerId())))) {
            throw new SecurityException("仅项目负责人或管理员可设置协作企划");
        }
    }
}
