package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ProjectCollaborator;
import com.emie.designpm.entity.User;
import com.emie.designpm.notification.service.NotificationWorkflowService;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectCollaborationServiceTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectCollaboratorRepository collaborators = mock(ProjectCollaboratorRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationWorkflowService notifications = mock(NotificationWorkflowService.class);
    private final ProjectCollaborationService service =
            new ProjectCollaborationService(projects, collaborators, users, notifications);
    private final AuthSession owner = new AuthSession("owner", "planner", "项目负责人");
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1L);
        project.setPlannerId("owner");
        project.setProductName("送审项目");
        project.setDeadline("2026-12-10");
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(projects.saveAndFlush(project)).thenReturn(project);
        when(users.findByUserIdIn(anyCollection())).thenReturn(List.of(planner("collaborator")));
    }

    @Test
    void addingCollaboratorKeepsOwnerAuditsAndNotifiesAfterCommit() {
        assertSame(project, service.replaceCollaborators(1L, List.of("collaborator", "collaborator"), owner));

        ArgumentCaptor<ProjectCollaborator> saved = ArgumentCaptor.forClass(ProjectCollaborator.class);
        verify(collaborators).save(saved.capture());
        assertEquals(1L, saved.getValue().getProjectId());
        assertEquals("collaborator", saved.getValue().getUserId());
        assertEquals("owner", saved.getValue().getAssignedBy());
        assertNotNull(saved.getValue().getCreatedAt());
        assertEquals("owner", project.getPlannerId());
        assertEquals(1, project.getLogs().size());
        assertTrue(project.getLogs().get(0).getAfterData().contains("collaborator"));
        verify(notifications)
                .notifyUserAfterCommit(
                        eq("PROJECT_COLLABORATION_ASSIGNED"),
                        eq("collaborator"),
                        eq("project"),
                        eq(1L),
                        eq("owner"),
                        argThat(context -> context.get("projectLink").equals("/?projectId=1")));
    }

    @Test
    void savingSameSelectionIsIdempotent() {
        when(collaborators.findByProjectId(1L)).thenReturn(List.of(member("collaborator")));

        service.replaceCollaborators(1L, List.of("collaborator"), owner);

        verify(collaborators, never()).save(any());
        verify(collaborators, never()).deleteAll(anyIterable());
        verify(projects, never()).saveAndFlush(any());
        verifyNoInteractions(notifications);
    }

    @Test
    void collaboratorCannotGrantAccessToMoreUsers() {
        assertThrows(
                SecurityException.class,
                () -> service.replaceCollaborators(
                        1L, List.of("collaborator"), new AuthSession("collaborator", "planner", "协作企划")));
        verifyNoInteractions(collaborators, users, notifications);
    }

    @Test
    void invalidOrInactiveTargetsAreRejectedBeforeAnyWrite() {
        User disabled = planner("collaborator");
        disabled.setStatus("disabled");
        when(users.findByUserIdIn(anyCollection())).thenReturn(List.of(disabled));
        assertThrows(
                IllegalArgumentException.class, () -> service.replaceCollaborators(1L, List.of("collaborator"), owner));
        assertThrows(IllegalArgumentException.class, () -> service.replaceCollaborators(1L, List.of("missing"), owner));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.replaceCollaborators(1L, Arrays.asList((String) null), owner));
        verify(collaborators, never()).save(any());
        verify(collaborators, never()).deleteAll(anyIterable());
        verifyNoInteractions(notifications);
    }

    @Test
    void removingCollaboratorDeletesMembershipAndKeepsOwner() {
        ProjectCollaborator member = member("collaborator");
        when(collaborators.findByProjectId(1L)).thenReturn(List.of(member));

        service.replaceCollaborators(1L, List.of(), owner);

        verify(collaborators).deleteAll(List.of(member));
        assertEquals("owner", project.getPlannerId());
        assertEquals(1, project.getLogs().size());
        verifyNoInteractions(notifications);
    }

    private User planner(String id) {
        return User.builder()
                .userId(id)
                .name(id)
                .role("planner")
                .status("active")
                .build();
    }

    private ProjectCollaborator member(String id) {
        ProjectCollaborator member = new ProjectCollaborator();
        member.setProjectId(1L);
        member.setUserId(id);
        return member;
    }
}
