package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import org.junit.jupiter.api.Test;

class ProjectTaskManagementPolicyTest {
    private final ProjectCollaboratorRepository collaborators = mock(ProjectCollaboratorRepository.class);
    private final ProjectTaskManagementPolicy policy = new ProjectTaskManagementPolicy(collaborators);

    @Test
    void collaboratorCanCreateAndManageOnlyTheirPublishedTasks() {
        Project project = project();
        SubTask ownTask = task(project, "collaborator");
        SubTask otherTask = task(project, "owner");
        AuthSession session = new AuthSession("collaborator", "planner", "协作企划");
        when(collaborators.existsByProjectIdAndUserId(1L, "collaborator")).thenReturn(true);

        assertTrue(policy.canCreate(project, session));
        assertTrue(policy.canManage(project, ownTask, session));
        assertFalse(policy.canManage(project, otherTask, session));
    }

    @Test
    void removingMembershipRevokesCreationAndManagementEvenForPublisher() {
        Project project = project();
        AuthSession session = new AuthSession("collaborator", "planner", "协作企划");
        when(collaborators.existsByProjectIdAndUserId(1L, "collaborator")).thenReturn(true, false, false);

        assertTrue(policy.canManage(project, task(project, "collaborator"), session));
        assertFalse(policy.canCreate(project, session));
        assertFalse(policy.canManage(project, task(project, "collaborator"), session));
    }

    @Test
    void ownerAndAdminCanManageEveryTaskButUnrelatedAndExecutionRolesCannot() {
        Project project = project();
        SubTask task = task(project, "collaborator");

        assertTrue(policy.canManage(project, task, new AuthSession("owner", "planner", "负责人")));
        assertTrue(policy.canManage(project, task, new AuthSession("admin", "admin", "管理员")));
        assertFalse(policy.canCreate(project, new AuthSession("other", "planner", "其他企划")));
        assertFalse(policy.canCreate(project, new AuthSession("owner", "designer", "设计师")));
        assertFalse(policy.canCreate(project, new AuthSession("", "admin", "无身份")));
        Project another = project();
        another.setId(2L);
        assertFalse(policy.canManage(project, task(another, "owner"), new AuthSession("owner", "planner", "负责人")));
    }

    private Project project() {
        Project project = new Project();
        project.setId(1L);
        project.setPlannerId("owner");
        return project;
    }

    private SubTask task(Project project, String publisherId) {
        SubTask task = new SubTask();
        task.setProject(project);
        task.setPublisherId(publisherId);
        return task;
    }
}
