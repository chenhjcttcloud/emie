package com.emie.designpm;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DepartmentRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.service.ProjectAccessService;
import com.emie.designpm.service.PermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectAccessServiceTest {

    @Test
    void departmentHeadCanViewMemberProjectsAndStatusScope() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        ProjectAccessService access = new ProjectAccessService(projects, users, departments);

        User head = user("designer-head", "designer", 7L);
        User member = user("designer-member", "designer", 7L);
        Department department = Department.builder()
                .id(7L).name("设计部").role("designer").headUserId(head.getUserId()).active(true).build();
        Project memberProject = new Project();
        memberProject.setId(99L);
        SubTask task = new SubTask();
        task.setDesignerId(member.getUserId());
        task.setAssigneeRole("designer");
        task.setStatus("accepted");
        memberProject.getTasks().add(task);

        when(users.findByUserId(head.getUserId())).thenReturn(Optional.of(head));
        when(departments.findByHeadUserId(head.getUserId())).thenReturn(Optional.of(department));
        when(users.findByDepartmentIdAndRole(7L, "designer")).thenReturn(List.of(head, member));
        when(projects.findByAssigneeViewLight(head.getUserId(), "designer")).thenReturn(List.of());
        when(projects.findByAssigneeViewLight(member.getUserId(), "designer")).thenReturn(List.of(memberProject));

        List<Project> visible = access.findVisibleProjectsLight("designer", head.getUserId());

        assertEquals(List.of(memberProject), visible);
        assertEquals(List.of(head, member), access.visibleUsers("designer", head.getUserId(), "designer"));
        assertTrue(access.canView(memberProject,
                new AuthController.AuthSession(head.getUserId(), "designer", "负责人")));
    }

    @Test
    void ordinaryMemberCannotViewAnotherMembersProject() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        ProjectAccessService access = new ProjectAccessService(projects, users, departments);
        User member = user("designer-1", "designer", 7L);
        Project otherProject = new Project();
        SubTask task = new SubTask();
        task.setDesignerId("designer-2");
        task.setAssigneeRole("designer");
        task.setStatus("accepted");
        otherProject.getTasks().add(task);

        when(users.findByUserId(member.getUserId())).thenReturn(Optional.of(member));
        when(departments.findByHeadUserId(member.getUserId())).thenReturn(Optional.empty());

        assertFalse(access.canView(otherProject,
                new AuthController.AuthSession(member.getUserId(), "designer", "成员")));
        assertEquals(List.of(member), access.visibleUsers("designer", member.getUserId(), "designer"));
    }

    @Test
    void configuredOwnScopePreventsDepartmentHeadFromReadingMemberProjectById() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        ProjectAccessService access = new ProjectAccessService(projects, users, departments, permissions);
        User head = user("designer-head", "designer", 7L);
        Project memberProject = new Project();
        SubTask task = new SubTask();
        task.setDesignerId("designer-member");
        task.setAssigneeRole("designer");
        memberProject.getTasks().add(task);

        when(permissions.has("designer", "project.detail.view")).thenReturn(true);
        when(permissions.scopes("designer", "project.detail.view")).thenReturn(List.of("own"));

        assertFalse(access.canView(memberProject,
                new AuthController.AuthSession(head.getUserId(), "designer", "负责人")));
    }

    @Test
    void configuredAllScopeAllowsCustomRoleToReadAnyProject() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        ProjectAccessService access = new ProjectAccessService(projects, users, departments, permissions);
        when(permissions.has("observer", "project.detail.view")).thenReturn(true);
        when(permissions.scopes("observer", "project.detail.view")).thenReturn(List.of("all"));

        assertTrue(access.canView(new Project(),
                new AuthController.AuthSession("observer-1", "observer", "观察员")));
    }

    private User user(String userId, String role, Long departmentId) {
        return User.builder().userId(userId).name(userId).role(role).departmentId(departmentId).build();
    }
}
