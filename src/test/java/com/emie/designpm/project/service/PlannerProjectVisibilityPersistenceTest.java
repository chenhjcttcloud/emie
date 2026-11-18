package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.admin.service.PermissionManagementService;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.admin.service.UserProjectScopeService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ProjectCollaborator;
import com.emie.designpm.entity.Role;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.reference.repository.DepartmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(
        properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"},
        showSql = false)
@ContextConfiguration(classes = PlannerProjectVisibilityPersistenceTest.Persistence.class)
class PlannerProjectVisibilityPersistenceTest {
    @Configuration
    @EnableJpaRepositories(
            basePackageClasses = {ProjectRepository.class, UserRepository.class, DepartmentRepository.class})
    @EntityScan(basePackageClasses = Project.class)
    @Import({
        PermissionService.class,
        UserProjectScopeService.class,
        ProjectAccessService.class,
        ProjectCollaborationService.class,
        ProjectTaskManagementPolicy.class
    })
    static class Persistence {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    EntityManager em;

    @Autowired
    ProjectRepository projects;

    @Autowired
    ProjectCollaboratorRepository collaborators;

    @Autowired
    ProjectAccessService access;

    @Autowired
    ProjectTaskManagementPolicy management;

    @Autowired
    ProjectCollaborationService collaborationService;

    @Autowired
    PermissionService permissions;

    @Autowired
    UserProjectScopeService scopes;

    @MockitoBean
    NotificationWorkflowService notifications;

    @BeforeEach
    void plannerPermissions() {
        Role role = Role.builder()
                .name("planner")
                .displayName("产品企划")
                .permissions("project.view,project.detail.view,subtask.view")
                .isSystem(true)
                .build();
        em.persist(role);
    }

    @Test
    void ownScopeIncludesOnlyOwnedExplicitCollaborationAndUnclaimedProjects() {
        user("assistant", "own", null);
        user("owner", "own", null);
        Project own = project("assistant");
        Project delegated = project("owner");
        Project hidden = project("owner");
        hidden.setSource("素材广场");
        Project unclaimed = project(null);
        unclaimed.setType("channel_custom");
        unclaimed.setStatus("pending_planner");
        invite(delegated, "assistant");
        em.flush();
        em.clear();

        Set<Long> expected = Set.of(own.getId(), delegated.getId(), unclaimed.getId());
        assertEquals(expected, Set.copyOf(access.findVisibleProjectIds("planner", "assistant")));
        assertEquals(expected, ids(access.findVisibleProjectsLight("planner", "assistant")));
        AuthSession assistant = session("assistant");
        assertEquals(expected, ids(access.findVisibleProjectsWithTasks(assistant)));
        assertEquals(3, access.countVisibleProjects("planner", "assistant", null, false));
        var query = new ProjectListQuery(null, null, null, null, null, null, null, false, PageRequest.of(0, 2));
        var page = access.findVisibleProjectsPage("planner", "assistant", query);
        assertEquals(3, page.getTotalElements());
        assertTrue(expected.containsAll(ids(page.getContent())));
        assertTrue(access.canView(projects.findById(delegated.getId()).orElseThrow(), assistant));
        assertFalse(access.canView(projects.findById(hidden.getId()).orElseThrow(), assistant));
        assertTrue(access.canView(projects.findById(unclaimed.getId()).orElseThrow(), assistant));
        assertFalse(management.canCreate(projects.findById(hidden.getId()).orElseThrow(), assistant));
        assertTrue(management.canCreate(projects.findById(delegated.getId()).orElseThrow(), assistant));
    }

    @Test
    void departmentScopeDoesNotInheritAnotherMembersExternalCollaborations() {
        Department department = Department.builder()
                .name("产品企划一部")
                .role("planner")
                .headUserId("head")
                .active(true)
                .build();
        em.persist(department);
        user("head", "department", department.getId());
        user("member", "own", department.getId());
        user("external", "own", null);
        Project headProject = project("head");
        Project memberProject = project("member");
        Project externalProject = project("external");
        invite(externalProject, "member");
        em.flush();
        em.clear();

        assertEquals(
                Set.of(headProject.getId(), memberProject.getId()),
                Set.copyOf(access.findVisibleProjectIds("planner", "head")));
        assertFalse(access.canView(projects.findById(externalProject.getId()).orElseThrow(), session("head")));
        assertTrue(access.canView(projects.findById(externalProject.getId()).orElseThrow(), session("member")));
    }

    @Test
    void removingCollaboratorRevokesExistingProjectAndPublishedTaskManagement() {
        user("owner", "own", null);
        user("assistant", "own", null);
        Project project = project("owner");
        invite(project, "assistant");
        em.flush();
        AuthSession assistant = session("assistant");
        SubTask published = new SubTask();
        published.setProject(project);
        published.setPublisherId("assistant");
        assertTrue(management.canManage(project, published, assistant));

        collaborationService.replaceCollaborators(project.getId(), List.of(), session("owner"));
        em.flush();
        em.clear();

        Project loaded = projects.findById(project.getId()).orElseThrow();
        assertTrue(collaborators.findByProjectId(project.getId()).isEmpty());
        assertFalse(access.canView(loaded, assistant));
        assertTrue(access.findVisibleProjectIds("planner", "assistant").isEmpty());
        assertFalse(management.canManage(loaded, published, assistant));
        assertTrue(management.canManage(loaded, published, session("owner")));
    }

    @Test
    void userOverrideChangesEffectiveScopesAndVersionWithoutChangingPeerScope() {
        User assistant = user("assistant", null, null);
        user("peer", null, null);
        project("assistant");
        Project peerProject = project("peer");
        em.flush();
        Object before = permissions.capabilities("planner", "assistant").get("permissionVersion");
        assertTrue(access.canView(peerProject, session("assistant")));

        scopes.updateScope(
                assistant.getId(),
                "own",
                new PermissionManagementService.Actor("admin", "管理员", "送审协作人员使用个人范围", "127.0.0.1"));
        em.flush();
        em.clear();

        assertEquals(List.of("own"), permissions.scopes("planner", "assistant", "project.view"));
        assertEquals(List.of("role_team"), permissions.scopes("planner", "peer", "project.view"));
        assertFalse(
                before.equals(permissions.capabilities("planner", "assistant").get("permissionVersion")));
        assertFalse(access.canView(projects.findById(peerProject.getId()).orElseThrow(), session("assistant")));
        assertEquals(1, access.findVisibleProjectIds("planner", "assistant").size());
        assertEquals(List.of("all"), permissions.scopes("admin", "admin", "project.view"));
    }

    private User user(String id, String scope, Long department) {
        User user = User.builder()
                .userId(id)
                .name(id)
                .role("planner")
                .status("active")
                .departmentId(department)
                .projectDataScope(scope)
                .build();
        em.persist(user);
        return user;
    }

    private Project project(String owner) {
        Project project = new Project();
        project.setType("regular");
        project.setStatus("in_progress");
        project.setProductName("送审项目");
        project.setDeadline("2026-12-31");
        project.setPlannerId(owner);
        em.persist(project);
        return project;
    }

    private void invite(Project project, String userId) {
        ProjectCollaborator member = new ProjectCollaborator();
        member.setProjectId(project.getId());
        member.setUserId(userId);
        member.setAssignedBy("owner");
        em.persist(member);
    }

    private AuthSession session(String userId) {
        return new AuthSession(userId, "planner", userId);
    }

    private Set<Long> ids(List<Project> values) {
        return values.stream().map(Project::getId).collect(Collectors.toSet());
    }
}
