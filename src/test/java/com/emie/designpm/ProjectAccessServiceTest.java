package com.emie.designpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import com.emie.designpm.reference.repository.DepartmentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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
                .id(7L)
                .name("设计部")
                .role("designer")
                .headUserId(head.getUserId())
                .active(true)
                .build();
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
        assertTrue(access.canView(memberProject, new AuthSession(head.getUserId(), "designer", "负责人")));
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

        assertFalse(access.canView(otherProject, new AuthSession(member.getUserId(), "designer", "成员")));
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
        when(permissions.scopes("designer", "designer-head", "project.detail.view"))
                .thenReturn(List.of("own"));

        assertFalse(access.canView(memberProject, new AuthSession(head.getUserId(), "designer", "负责人")));
    }

    @Test
    void configuredAllScopeAllowsCustomRoleToReadAnyProject() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        ProjectAccessService access = new ProjectAccessService(projects, users, departments, permissions);
        when(permissions.has("observer", "project.detail.view")).thenReturn(true);
        when(permissions.scopes("observer", "observer-1", "project.detail.view"))
                .thenReturn(List.of("all"));

        assertTrue(access.canView(new Project(), new AuthSession("observer-1", "observer", "观察员")));
    }

    @Test
    void promotionAliasCanViewItsAssignedProject() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        ProjectAccessService access = new ProjectAccessService(projects, users, departments);
        Project project = new Project();
        SubTask task = new SubTask();
        task.setDesignerId("promotion-1");
        task.setAssigneeRole("promotion");
        project.getTasks().add(task);

        assertTrue(access.canView(project, new AuthSession("promotion-1", "Promotion", "产品推广")));
    }

    @Test
    void ownPlannerScopeKeepsActualViewerSeparateFromOtherOwnersForEveryQuery() {
        ProjectRepository projects = mock(ProjectRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        ProjectAccessService access = new ProjectAccessService(
                projects, mock(UserRepository.class), mock(DepartmentRepository.class), permissions);
        when(permissions.has(eq("planner"), anyString())).thenReturn(true);
        when(permissions.scopes(eq("planner"), eq("xiaojie"), anyString())).thenReturn(List.of("own"));
        Project mine = new Project();
        mine.setId(11L);
        mine.setPlannerId("xiaojie");
        Project other = new Project();
        other.setId(12L);
        other.setPlannerId("other");
        when(projects.findPlannerProjectsLight(List.of("xiaojie"), "xiaojie")).thenReturn(List.of(mine));
        when(projects.findPlannerProjectsWithTasks(List.of("xiaojie"), "xiaojie"))
                .thenReturn(List.of(mine));
        when(projects.findVisiblePage(any(), eq("planner"), eq(List.of("xiaojie")), eq("xiaojie")))
                .thenReturn(Page.empty());
        when(projects.countVisible(any(), eq("planner"), eq(List.of("xiaojie")), eq("xiaojie")))
                .thenReturn(1L);
        when(projects.findVisibleIds(any(), eq("planner"), eq(List.of("xiaojie")), eq("xiaojie")))
                .thenReturn(List.of(11L));
        assertEquals(List.of(mine), access.findVisibleProjectsLight("planner", "xiaojie"));
        assertEquals(List.of(mine), access.findVisibleProjectsWithTasks(new AuthSession("xiaojie", "planner", "小洁")));
        access.findVisibleProjectsPage("planner", "xiaojie", "regular", false, PageRequest.of(0, 15));
        assertEquals(1L, access.countVisibleProjects("planner", "xiaojie", null, false));
        assertEquals(List.of(11L), access.findVisibleProjectIds("planner", "xiaojie"));
        assertTrue(access.canView(mine, new AuthSession("xiaojie", "planner", "小洁")));
        assertFalse(access.canView(other, new AuthSession("xiaojie", "planner", "小洁")));
        other.setSource("素材广场");
        assertFalse(access.canView(other, new AuthSession("xiaojie", "planner", "小洁")));
        when(projects.isCollaborator(12L, "xiaojie")).thenReturn(true);
        assertTrue(access.canView(other, new AuthSession("xiaojie", "planner", "小洁")));
        assertEquals(List.of(), access.departmentTaskUserIds("planner", "xiaojie"));
        verify(projects).findVisiblePage(any(), eq("planner"), eq(List.of("xiaojie")), eq("xiaojie"));
    }

    @Test
    void plannerDepartmentScopeUsesActiveHeadDepartmentAndOtherwiseFallsBackToOwn() {
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        ProjectAccessService access = new ProjectAccessService(projects, users, departments, permissions);
        when(permissions.has(eq("planner"), anyString())).thenReturn(true);
        when(permissions.scopes(eq("planner"), anyString(), anyString())).thenReturn(List.of("department"));
        Department department = Department.builder()
                .id(7L)
                .role("planner")
                .active(true)
                .headUserId("head")
                .build();
        when(departments.findByHeadUserId("head")).thenReturn(Optional.of(department));
        when(users.findByDepartmentIdAndRole(7L, "planner"))
                .thenReturn(List.of(user("head", "planner", 7L), user("member", "planner", 7L)));
        access.findVisibleProjectIds("planner", "head");
        verify(projects).findVisibleIds(any(), eq("planner"), eq(List.of("head", "member")), eq("head"));
        assertEquals(List.of("member"), access.departmentTaskUserIds("planner", "head"));
        access.findVisibleProjectIds("planner", "member");
        verify(projects).findVisibleIds(any(), eq("planner"), eq(List.of("member")), eq("member"));
    }

    private User user(String userId, String role, Long departmentId) {
        return User.builder()
                .userId(userId)
                .name(userId)
                .role(role)
                .departmentId(departmentId)
                .build();
    }
}
