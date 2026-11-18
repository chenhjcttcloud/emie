package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectCommandDetailVisibilityTest {
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ActivityLogRepository logs = mock(ActivityLogRepository.class);
    private final ScoringRepository scoring = mock(ScoringRepository.class);
    private ProjectViewSupport view;
    private Project project;

    @BeforeEach
    void setUp() {
        view = spy(new ProjectViewSupport(
                projects,
                scoring,
                logs,
                mock(SubTaskRepository.class),
                access,
                mock(ProjectWorkflowService.class),
                mock(SubTaskCommandService.class)));
        project = new Project();
        project.setId(1L);
        project.setProjectCode("EMIE2026110001");
        project.setType("regular");
        project.setStatus("in_progress");
        project.setProductName("送审项目");
        project.setPlannerId("owner");
        project.setProductRequirements("SECRET_PROJECT_REQUIREMENTS");
        project.setReferenceImagesJson("SECRET_PROJECT_REFERENCES");
        project.setAttachmentsJson("SECRET_PROJECT_ATTACHMENTS");
        project.setProductArchiveJson("SECRET_PRODUCT_ARCHIVE");
        when(projects.getProjectById(1L)).thenReturn(Optional.of(project));
    }

    @Test
    void revokedCollaboratorExecutionResponseOnlyIncludesTheirAssignedTask() throws Exception {
        AuthSession session = new AuthSession("collaborator", "planner", "协作企划");
        SubTask assigned = task(2L, "collaborator", "planner");
        assigned.setDetails("允许读取的本人任务资料");
        SubTask hidden = task(3L, "other", "planner");
        hidden.setName("SECRET_OTHER_TASK");
        SubTask roleMismatch = task(4L, "collaborator", "designer");
        roleMismatch.setName("SECRET_OTHER_ROLE");
        SubTask historicalPublished = task(5L, "other", "designer");
        historicalPublished.setPublisherId("collaborator");
        historicalPublished.setName("SECRET_FORMER_PUBLISHER_TASK");
        project.getTasks().addAll(List.of(assigned, hidden, roleMismatch, historicalPublished));

        ProjectDetailDTO result = view.toCommandDetail(project, session);

        assertEquals(1L, result.getId());
        assertEquals("送审项目", result.getProductName());
        assertEquals(
                List.of(2L),
                result.getTasks().stream().map(task -> task.getId()).toList());
        assertEquals("允许读取的本人任务资料", result.getTasks().getFirst().getDetails());
        assertEquals(List.of(), result.getLogs());
        assertEquals(List.of(), result.getCollaborators());
        assertNull(result.getProductRequirements());
        assertNull(result.getReferenceImagesJson());
        assertNull(result.getAttachmentsJson());
        assertNull(result.getProductArchiveJson());
        assertNull(result.getSubTaskWorkflow());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("SECRET_"));
        verifyNoInteractions(logs, scoring);
    }

    @Test
    void historicalDesignerTaskRoleRemainsExecutableWithoutProjectRead() {
        AuthSession session = new AuthSession("designer", "designer", "设计师");
        project.getTasks()
                .addAll(List.of(task(2L, "designer", null), task(3L, "designer", ""), task(4L, "designer", "planner")));

        ProjectDetailDTO result = view.toCommandDetail(project, session);

        assertEquals(
                List.of(2L, 3L),
                result.getTasks().stream().map(task -> task.getId()).toList());
    }

    @Test
    void ownerAndAuthorizedCollaboratorReceiveNormalProjectDetail() {
        for (String userId : List.of("owner", "collaborator")) {
            AuthSession session = new AuthSession(userId, "planner", userId);
            ProjectDetailDTO full = new ProjectDetailDTO();
            full.setProductRequirements("全部授权资料");
            when(access.canView(project, session)).thenReturn(true);
            doReturn(full).when(view).toDetail(project);

            assertSame(full, view.toCommandDetail(project, session));
        }
    }

    private SubTask task(Long id, String assignee, String role) {
        SubTask task = new SubTask();
        task.setId(id);
        task.setName("本人任务");
        task.setProject(project);
        task.setDesignerId(assignee);
        task.setAssigneeRole(role);
        task.setStatus("accepted");
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }
}
