package com.emie.designpm;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.RedisSessionStore;
import com.emie.designpm.service.NotificationRetryService;
import com.emie.designpm.service.PermissionService;
import com.emie.designpm.service.SyncQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Request-level regression coverage for the DTO boundaries that previously relied on OSIV.
 * Test data is committed before MockMvc runs so the test's own persistence context cannot
 * accidentally keep lazy associations usable during controller serialization.
 */
@SpringBootTest(properties = {
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

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SubTaskRepository subTasks;
    @Autowired ActivityLogRepository activityLogs;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean RedisSessionStore redisSessionStore;
    @MockitoBean SyncQueueService syncQueueService;
    @MockitoBean NotificationRetryService notificationRetryService;
    @MockitoBean PermissionService permissionService;

    private Long projectId;
    private Long taskId;
    private String token;

    @BeforeEach
    void createCommittedProjectGraph() {
        when(permissionService.has(anyString(), anyString())).thenReturn(true);
        when(permissionService.scopes(anyString(), anyString())).thenReturn(java.util.List.of("all"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            activityLogs.deleteAll();
            subTasks.deleteAll();
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
        token = AuthController.generateToken("planner-osiv", "admin", "OSIV 测试员");
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
}
