package com.emie.designpm.controller;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.ProjectAccessService;
import com.emie.designpm.service.ProjectService;
import com.emie.designpm.service.ProjectWorkflowService;
import com.emie.designpm.service.FeishuChatService;
import com.emie.designpm.service.SubTaskCommandService;
import com.emie.designpm.service.ProjectLifecycleCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

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
        SubTaskCommandService commands = mock(SubTaskCommandService.class);
        doThrow(new RuntimeException("仅项目负责人企划可创建子任务"))
                .when(commands).addSubTask(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.anyMap());
        ProjectController controller = new ProjectController(projects, mock(ScoringRepository.class),
                mock(ActivityLogRepository.class), mock(SubTaskRepository.class), mock(ProjectAccessService.class),
                mock(ProjectWorkflowService.class), null, commands, mock(ProjectLifecycleCommandService.class));

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

    @Test
    void taskMarketIsVisibleToDesignersAndReturnsOnlyRepositoryResults() {
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        Project project = project(11L, "新品包装", "");
        SubTask task = task(23L, project, null);
        task.setStatus("pending");
        task.setAssigneeRole("designer");
        task.setAllocationStatus("market_open");
        when(tasks.findOpenDesignerMarketTasks()).thenReturn(List.of(task));

        var response = controller(tasks, mock(ScoringRepository.class))
                .getTaskMarket(request("designer-1", "designer"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertEquals("market_open", body.getFirst().get("allocationStatus"));
        assertEquals("market", body.getFirst().get("relation"));
    }

    @Test
    void taskMarketRejectsRolesWithoutMarketVisibility() {
        var response = controller(mock(SubTaskRepository.class), mock(ScoringRepository.class))
                .getTaskMarket(request("sales-1", "sales"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void plannerCanCreateMissingChatForChannelAndRegularProjects() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        FeishuChatService chats = mock(FeishuChatService.class);
        ProjectController controller = new ProjectController(projects, mock(ScoringRepository.class),
                mock(ActivityLogRepository.class), mock(SubTaskRepository.class), mock(ProjectAccessService.class),
                mock(ProjectWorkflowService.class));
        ReflectionTestUtils.setField(controller, "feishuChatService", chats);
        when(chats.enabled()).thenReturn(true);
        when(chats.createChat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn("chat-1", "chat-2");
        when(projects.saveProject(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(projects.computeProjectStatus(org.mockito.ArgumentMatchers.any())).thenReturn("planner_accepted");

        Project channel = project(12L, "渠道新品", "");
        channel.setType("channel_custom");
        channel.setSalesId("sales-1");
        channel.setFeishuChatStatus("not_created");
        Project regular = project(13L, "常规新品", "");
        regular.setPlannerId("planner-owner");
        regular.setFeishuChatStatus("not_created");
        when(projects.getProjectById(12L)).thenReturn(java.util.Optional.of(channel));
        when(projects.getProjectById(13L)).thenReturn(java.util.Optional.of(regular));

        var channelResponse = controller.createProjectChat(12L, request("planner-other", "planner"));
        var regularResponse = controller.createProjectChat(13L, request("planner-other", "planner"));
        assertEquals(HttpStatus.OK, channelResponse.getStatusCode(), String.valueOf(channelResponse.getBody()));
        assertEquals(HttpStatus.OK, regularResponse.getStatusCode(), String.valueOf(regularResponse.getBody()));
        assertEquals("created", channel.getFeishuChatStatus());
        assertEquals("created", regular.getFeishuChatStatus());
    }

    private ProjectController controller(SubTaskRepository tasks, ScoringRepository scoring) {
        return new ProjectController(mock(ProjectService.class), scoring, mock(ActivityLogRepository.class),
                tasks, mock(ProjectAccessService.class), mock(ProjectWorkflowService.class));
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
        project.setCreatedAt(java.time.LocalDateTime.now());
        project.setUpdatedAt(java.time.LocalDateTime.now());
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
