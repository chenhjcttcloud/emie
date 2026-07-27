package com.emie.designpm.controller;

import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.entity.Project;
import com.emie.designpm.service.PermissionService;
import com.emie.designpm.service.ProjectAccessService;
import com.emie.designpm.service.ProjectService;
import com.emie.designpm.service.ProjectWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectCreationPermissionTest {

    @Test
    void channelCreationRequiresNormalizedPermissionBeforeBusinessServiceRuns() {
        ProjectService projects = mock(ProjectService.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("sales", "project.channel.create")).thenReturn(false);
        ProjectController controller = controller(projects, permissions);

        var response = controller.createProject(
                Map.of("type", "channel_custom", "productName", "新品"),
                request("sales-1", "sales"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("project.channel.create", ((Map<?, ?>) response.getBody()).get("permission"));
        verifyNoInteractions(projects);
    }

    @Test
    void regularCreationRequiresItsOwnPermissionAndRejectsUnknownType() {
        ProjectService projects = mock(ProjectService.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("planner", "project.regular.create")).thenReturn(false);
        ProjectController controller = controller(projects, permissions);

        assertEquals(HttpStatus.FORBIDDEN, controller.createProject(
                Map.of("type", "regular"), request("planner-1", "planner")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.createProject(
                Map.of("type", "other"), request("planner-1", "planner")).getStatusCode());
        verifyNoInteractions(projects);
    }

    @Test
    void projectEditRequiresTypePermissionBeforeOwnershipRule() {
        ProjectService projects = mock(ProjectService.class);
        PermissionService permissions = mock(PermissionService.class);
        Project project = new Project();
        project.setId(12L);
        project.setType("channel_custom");
        project.setSalesId("sales-1");
        when(projects.getProjectById(12L)).thenReturn(java.util.Optional.of(project));
        when(permissions.has("sales", "project.channel.edit")).thenReturn(false);
        ProjectController controller = controller(projects, permissions);

        var response = controller.updateProjectInformation(
                12L, Map.of("productName", "新品"), request("sales-1", "sales"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("project.channel.edit", ((Map<?, ?>) response.getBody()).get("permission"));
        verify(projects).getProjectById(12L);
    }

    @Test
    void subTaskCreationRequiresPermissionBeforeBusinessServiceRuns() {
        ProjectService projects = mock(ProjectService.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("planner", "subtask.create")).thenReturn(false);
        ProjectController controller = controller(projects, permissions);

        var response = controller.addTask(
                12L, Map.of("name", "包装设计"), request("planner-1", "planner"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("subtask.create", ((Map<?, ?>) response.getBody()).get("permission"));
        verifyNoInteractions(projects);
    }

    @Test
    void subTaskEditRequiresPermissionBeforeBusinessServiceRuns() {
        ProjectService projects = mock(ProjectService.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("planner", "subtask.edit")).thenReturn(false);
        ProjectController controller = controller(projects, permissions);

        var response = controller.updateTask(
                12L, 30L, Map.of("name", "新版包装"), request("planner-1", "planner"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("subtask.edit", ((Map<?, ?>) response.getBody()).get("permission"));
        verifyNoInteractions(projects);
    }

    private ProjectController controller(ProjectService projects, PermissionService permissions) {
        return new ProjectController(projects, mock(ScoringRepository.class),
                mock(ActivityLogRepository.class), mock(SubTaskRepository.class),
                mock(ProjectAccessService.class), mock(ProjectWorkflowService.class), permissions);
    }

    private MockHttpServletRequest request(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthController.AuthSession(userId, role, "测试用户"));
        return request;
    }
}
