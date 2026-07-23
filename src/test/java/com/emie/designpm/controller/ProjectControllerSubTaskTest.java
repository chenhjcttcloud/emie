package com.emie.designpm.controller;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.ProjectAccessService;
import com.emie.designpm.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class ProjectControllerSubTaskTest {

    @Test
    void addTaskReturnsBusinessErrorInsteadOfServerException() {
        ProjectService projects = mock(ProjectService.class);
        doThrow(new RuntimeException("仅项目负责人企划可创建子任务"))
                .when(projects).addSubTask(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.anyMap());
        ProjectController controller = new ProjectController(projects, mock(ScoringRepository.class),
                mock(ActivityLogRepository.class), mock(SubTaskRepository.class), mock(ProjectAccessService.class));

        var response = controller.addTask(9L, Map.of("name", "包装设计"),
                request("planner-2", "planner"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "仅项目负责人企划可创建子任务"), response.getBody());
    }

    @Test
    void mySubTasksExposeProductNameInsteadOfProductRequirements() {
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        Project project = project(9L, "蓝牙音箱新品", "完成外观与包装设计");
        SubTask task = task(21L, project, "designer-1");
        when(tasks.findMySubTasks("designer-1")).thenReturn(List.of(task));
        when(scoring.findBySubTaskIds(List.of(21L))).thenReturn(List.of());

        var response = controller(tasks, scoring).getMySubTasks(request("designer-1", "designer"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) (List<?>) response.getBody();
        assertEquals("蓝牙音箱新品", body.getFirst().get("projectName"));
    }

    @Test
    void mySubTasksFallBackForHistoricalProjectWithoutProductName() {
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ScoringRepository scoring = mock(ScoringRepository.class);
        Project project = project(10L, null, "历史项目需求说明");
        SubTask task = task(22L, project, "designer-1");
        when(tasks.findMySubTasks("designer-1")).thenReturn(List.of(task));
        when(scoring.findBySubTaskIds(List.of(22L))).thenReturn(List.of());

        var response = controller(tasks, scoring).getMySubTasks(request("designer-1", "designer"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) (List<?>) response.getBody();
        assertEquals("历史项目需求说明", body.getFirst().get("projectName"));
    }

    private ProjectController controller(SubTaskRepository tasks, ScoringRepository scoring) {
        return new ProjectController(mock(ProjectService.class), scoring, mock(ActivityLogRepository.class),
                tasks, mock(ProjectAccessService.class));
    }

    private MockHttpServletRequest request(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthController.AuthSession(userId, role, "测试用户"));
        return request;
    }

    private Project project(Long id, String productName, String requirements) {
        Project project = new Project();
        project.setId(id);
        project.setType("regular");
        project.setProductName(productName);
        project.setProductRequirements(requirements);
        return project;
    }

    private SubTask task(Long id, Project project, String designerId) {
        SubTask task = new SubTask();
        task.setId(id);
        task.setName("包装设计");
        task.setStatus("accepted");
        task.setDesignerId(designerId);
        task.setProject(project);
        return task;
    }
}
