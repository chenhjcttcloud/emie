package com.emie.designpm.project.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import com.emie.designpm.project.service.ProjectService;
import com.emie.designpm.project.service.ProjectViewSupport;
import com.emie.designpm.project.service.ProjectWorkflowService;
import com.emie.designpm.project.service.SubTaskCommandService;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ProjectTaskResponseVisibilityTest {
    @Test
    void deliverySucceedsForAssignedPlannerWithoutReturningHiddenProjectContent() {
        ProjectService projects = mock(ProjectService.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskCommandService commands = mock(SubTaskCommandService.class);
        Project project = new Project();
        project.setId(1L);
        project.setType("regular");
        project.setStatus("in_progress");
        project.setProductName("送审项目");
        project.setPlannerId("owner");
        project.setAttachmentsJson("PRIVATE_PROJECT_FILE");
        SubTask assigned = task(2L, project, "collaborator");
        assigned.setStatus("submitted_for_review");
        project.getTasks().addAll(List.of(assigned, task(3L, project, "owner")));
        when(projects.getProjectById(1L)).thenReturn(Optional.of(project));
        when(commands.taskDeliver(eq(1L), eq(2L), anyMap())).thenReturn(project);
        ProjectViewSupport view = new ProjectViewSupport(
                projects,
                mock(ScoringRepository.class),
                mock(ActivityLogRepository.class),
                mock(SubTaskRepository.class),
                access,
                mock(ProjectWorkflowService.class),
                commands);
        ProjectTaskController controller =
                new ProjectTaskController(projects, commands, view, new ProjectRequestSupport(null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("collaborator", "planner", "协作企划"));

        var response = controller.taskDeliver(1L, 2L, Map.of("deliverables", "交付资料"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ProjectDetailDTO dto = (ProjectDetailDTO) response.getBody();
        assertNotNull(dto);
        assertEquals(
                List.of(2L), dto.getTasks().stream().map(task -> task.getId()).toList());
        assertEquals("submitted_for_review", dto.getTasks().getFirst().getStatus());
        assertEquals(List.of(), dto.getLogs());
        assertNull(dto.getAttachmentsJson());
        verify(commands).taskDeliver(eq(1L), eq(2L), argThat(body -> body.get("currentUserId")
                .equals("collaborator")));
    }

    private SubTask task(Long id, Project project, String assignee) {
        SubTask task = new SubTask();
        task.setId(id);
        task.setName("送审资料");
        task.setProject(project);
        task.setDesignerId(assignee);
        task.setAssigneeRole("planner");
        task.setStatus("accepted");
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }
}
