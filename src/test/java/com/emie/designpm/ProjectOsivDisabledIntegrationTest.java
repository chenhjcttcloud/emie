package com.emie.designpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSessions;
import com.emie.designpm.auth.RedisSessionStore;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ProjectCollaborator;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.notification.service.NotificationRetryService;
import com.emie.designpm.project.repository.ProjectCollaboratorRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRejectionCycleRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.sync.service.SyncQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Request-level regression coverage for the DTO boundaries that previously relied on OSIV.
 * Test data is committed before MockMvc runs so the test's own persistence context cannot
 * accidentally keep lazy associations usable during controller serialization.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:osiv-regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false",
            "spring.flyway.enabled=false",
            "spring.task.scheduling.enabled=false",
            "app.feishu.sync-worker-enabled=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectOsivDisabledIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProjectRepository projects;

    @Autowired
    SubTaskRepository subTasks;

    @Autowired
    ActivityLogRepository activityLogs;

    @Autowired
    ProjectCollaboratorRepository collaborators;

    @Autowired
    SubTaskRejectionCycleRepository rejectionCycles;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    RedisSessionStore redisSessionStore;

    @MockitoBean
    SyncQueueService syncQueueService;

    @MockitoBean
    NotificationRetryService notificationRetryService;

    @MockitoBean
    PermissionService permissionService;

    private Long projectId;
    private Long taskId;
    private String token;

    @BeforeEach
    void createCommittedProjectGraph() {
        when(permissionService.has(anyString(), anyString())).thenReturn(true);
        when(permissionService.scopes(anyString(), anyString(), anyString())).thenReturn(java.util.List.of("all"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            activityLogs.deleteAll();
            rejectionCycles.deleteAll();
            subTasks.deleteAll();
            collaborators.deleteAll();
            projects.deleteAll();

            Project project = new Project();
            project.setType("channel_custom");
            project.setStatus("pending_planner");
            project.setSalesId("sales-osiv");
            project.setSalesName("测试销售");
            project.setPlannerId("planner-osiv");
            project.setPlannerName("测试企划");
            project.setProductName("OSIV 回归项目");
            project.setDeadline("2026-12-31");
            project.setProductRequirements("项目详情必须在事务外完整输出");
            project.setReferenceImagesJson("[]");
            project.setAttachmentsJson("[]");
            projects.saveAndFlush(project);

            SubTask task = new SubTask();
            task.setProject(project);
            task.setName("OSIV 子任务");
            task.setStatus("pending");
            task.setWorkflowStage("design");
            task.setPlannedDate("2026-11-30");
            task.setDesignerId("planner-osiv");
            task.setDesignerName("测试企划");
            task.setPublisherId("planner-osiv");
            task.setPublisherName("测试企划");
            task.setPublisherRole("planner");
            task.setAssigneeRole("planner");
            task.setAllocationStatus("direct_assigned");
            task.setReferenceImagesJson("[]");
            task.setAttachmentsJson("[]");
            subTasks.saveAndFlush(task);

            activityLogs.saveAndFlush(new ActivityLog("已创建回归数据", "测试企划", "planner", project));
            projectId = project.getId();
            taskId = task.getId();
        });
        token = AuthSessions.generateToken("planner-osiv", "admin", "OSIV 测试员");
    }

    @Test
    void projectDetailSerializesCommittedTasksAndLogsWithOsivDisabled() throws Exception {
        mvc.perform(get("/api/projects/{id}", projectId).header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("OSIV 回归项目"))
                .andExpect(jsonPath("$.tasks[0].id").value(taskId))
                .andExpect(jsonPath("$.tasks[0].name").value("OSIV 子任务"))
                .andExpect(jsonPath("$.logs[0].action").value("已创建回归数据"));
    }

    @Test
    void pessimisticLockWriteReturnsDetachedProjectDetail() throws Exception {
        mvc.perform(post("/api/projects/{id}/accept", projectId)
                        .header("X-Auth-Token", token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("planner_accepted"))
                .andExpect(jsonPath("$.tasks[0].id").value(taskId))
                .andExpect(jsonPath("$.logs[?(@.action == '产品企划接单，请添加子任务')]").exists());
    }

    @Test
    void myTasksSerializesTaskCollectionsWithOsivDisabled() throws Exception {
        mvc.perform(get("/api/projects/my-tasks").header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(projectId))
                .andExpect(jsonPath("$[0].tasks[0].id").value(taskId));
    }

    @Test
    void mySubtasksSerializesLazyProjectReferenceWithOsivDisabled() throws Exception {
        mvc.perform(get("/api/projects/my-subtasks").header("X-Auth-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(taskId))
                .andExpect(jsonPath("$[0].projectId").value(projectId))
                .andExpect(jsonPath("$[0].projectName").value("OSIV 回归项目"));
    }

    @Test
    void assigneeTaskAcceptLoadsGraphBeforeCheckingOwnProjectScope() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Project project = projects.findById(projectId).orElseThrow();
            project.setStatus("in_progress");
            SubTask task = subTasks.findById(taskId).orElseThrow();
            task.setAssigneeRole("supplychain");
        });
        when(permissionService.scopes("supplychain", "planner-osiv", "project.detail.view"))
                .thenReturn(java.util.List.of("own"));
        String assigneeToken = AuthSessions.generateToken("planner-osiv", "supplychain", "测试执行人");

        mvc.perform(post("/api/projects/{projectId}/tasks/{taskId}/accept", projectId, taskId)
                        .header("X-Auth-Token", assigneeToken)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value(taskId))
                .andExpect(jsonPath("$.tasks[0].status").value("accepted"));
    }

    @Test
    void assignedPlannerCanAcceptWithoutReceivingOtherProjectTasksOrRequirements() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Project project = projects.findById(projectId).orElseThrow();
            project.setStatus("in_progress");
            project.setPlannerId("other-owner");
            SubTask other = new SubTask();
            other.setProject(project);
            other.setName("其他人的私有任务");
            other.setStatus("pending");
            other.setPlannedDate("2026-12-31");
            other.setAssigneeRole("planner");
            other.setDesignerId("other-planner");
            subTasks.saveAndFlush(other);
        });
        when(permissionService.scopes("planner", "planner-osiv", "project.detail.view"))
                .thenReturn(java.util.List.of("own"));
        String assigneeToken = AuthSessions.generateToken("planner-osiv", "planner", "测试企划执行人");

        mvc.perform(post("/api/projects/{projectId}/tasks/{taskId}/accept", projectId, taskId)
                        .header("X-Auth-Token", assigneeToken)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].id").value(taskId))
                .andExpect(jsonPath("$.logs.length()").value(0))
                .andExpect(jsonPath("$.productRequirements").isEmpty())
                .andExpect(jsonPath("$.referenceImagesJson").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void currentCollaboratorCanReviewTheirPublishedTaskWithOsivDisabled(String action) throws Exception {
        prepareCollaboratorReview("collaborator-osiv", true);

        reviewAsCollaborator(action, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value(taskId))
                .andExpect(jsonPath("$.tasks[0].status").value("approve".equals(action) ? "completed" : "rejected"));

        assertEquals("approve".equals(action) ? "completed" : "rejected", storedTaskStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void collaboratorCannotForgePublisherOrOwnerToReviewAnotherPublishersTask(String action) throws Exception {
        prepareCollaboratorReview("planner-osiv", true);

        reviewAsCollaborator(
                        action,
                        Map.of(
                                "publisherId",
                                "collaborator-osiv",
                                "currentUserId",
                                "planner-osiv",
                                "currentRole",
                                "admin"))
                .andExpect(status().isBadRequest());

        assertEquals("delivered", storedTaskStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void formerCollaboratorCannotReviewTheirPreviouslyPublishedTask(String action) throws Exception {
        prepareCollaboratorReview("collaborator-osiv", false);

        reviewAsCollaborator(action, Map.of()).andExpect(status().isBadRequest());

        assertEquals("delivered", storedTaskStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void collaboratorReviewStillRequiresRoleFunctionPermission(String action) throws Exception {
        prepareCollaboratorReview("collaborator-osiv", true);
        String permission = "subtask.review.first." + action;
        when(permissionService.has("planner", permission)).thenReturn(false);

        reviewAsCollaborator(action, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.permission").value(permission));

        assertEquals("delivered", storedTaskStatus());
    }

    private void prepareCollaboratorReview(String publisherId, boolean activeCollaborator) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Project project = projects.findById(projectId).orElseThrow();
            project.setStatus("in_progress");
            SubTask task = subTasks.findById(taskId).orElseThrow();
            task.setStatus("delivered");
            task.setPublisherId(publisherId);
            if (activeCollaborator) {
                ProjectCollaborator collaborator = new ProjectCollaborator();
                collaborator.setProjectId(projectId);
                collaborator.setUserId("collaborator-osiv");
                collaborator.setAssignedBy("planner-osiv");
                collaborators.saveAndFlush(collaborator);
            }
        });
    }

    private org.springframework.test.web.servlet.ResultActions reviewAsCollaborator(
            String action, Map<String, Object> overrides) throws Exception {
        String collaboratorToken = AuthSessions.generateToken("collaborator-osiv", "planner", "协作企划");
        Map<String, Object> body = new LinkedHashMap<>(overrides);
        body.put("comments", "检查交付成果");
        body.put("requiredCompletionDate", "2026-12-25");
        return mvc.perform(post("/api/projects/{projectId}/tasks/{taskId}/{action}", projectId, taskId, action)
                .header("X-Auth-Token", collaboratorToken)
                .contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(body)));
    }

    private String storedTaskStatus() {
        return new TransactionTemplate(transactionManager)
                .execute(status -> subTasks.findById(taskId).orElseThrow().getStatus());
    }
}
