package com.emie.designpm.project.service;

import com.emie.designpm.admin.service.PermissionCatalog;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 协作企划可发布和管理自己发布的子任务，项目审核仍交给项目负责人。 */
@Service
public class ProjectTaskManagementPolicy {
    private final ProjectCollaboratorRepository collaborators;

    public ProjectTaskManagementPolicy(ProjectCollaboratorRepository collaborators) {
        this.collaborators = collaborators;
    }

    public boolean canCreate(Project project, AuthSession session) {
        if (project == null
                || session == null
                || session.userId() == null
                || session.userId().isBlank()) return false;
        String role = PermissionCatalog.normalizeRole(session.role());
        if ("admin".equals(role)) return true;
        return "planner".equals(role)
                && (Objects.equals(project.getPlannerId(), session.userId())
                        || (project.getId() != null
                                && collaborators != null
                                && collaborators.existsByProjectIdAndUserId(project.getId(), session.userId())));
    }

    public boolean canManage(Project project, SubTask task, AuthSession session) {
        if (task == null
                || !canCreate(project, session)
                || task.getProject() == null
                || !Objects.equals(task.getProject().getId(), project.getId())) return false;
        return "admin".equals(PermissionCatalog.normalizeRole(session.role()))
                || Objects.equals(project.getPlannerId(), session.userId())
                || Objects.equals(task.getPublisherId(), session.userId());
    }

    public void requireCreate(Project project, Map<String, Object> actor) {
        if (!canCreate(project, session(actor))) throw new SecurityException("仅项目负责人或协作企划可创建子任务");
    }

    public void requireManage(Project project, SubTask task, Map<String, Object> actor) {
        if (!canManage(project, task, session(actor))) throw new SecurityException("仅项目负责人或任务发布的协作企划可管理子任务");
    }

    private AuthSession session(Map<String, Object> actor) {
        return new AuthSession(
                Objects.toString(actor.get("currentUserId"), ""),
                Objects.toString(actor.get("currentRole"), ""),
                Objects.toString(actor.get("currentUser"), ""));
    }
}
