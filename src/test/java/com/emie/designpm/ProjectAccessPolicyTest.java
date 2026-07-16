package com.emie.designpm;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.util.ProjectAccessPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAccessPolicyTest {

    @Test
    void supplyChainCannotSeeUnassignedDesignerTask() {
        Project project = projectWithPendingTask("designer");

        assertFalse(ProjectAccessPolicy.canView(project,
                new AuthController.AuthSession("supply-1", "supplychain", "供应链")));
        assertTrue(ProjectAccessPolicy.canView(project,
                new AuthController.AuthSession("designer-1", "designer", "设计师")));
    }

    @Test
    void designerCannotSeeUnassignedSupplyChainTask() {
        Project project = projectWithPendingTask("supplychain");

        assertFalse(ProjectAccessPolicy.canView(project,
                new AuthController.AuthSession("designer-1", "designer", "设计师")));
        assertTrue(ProjectAccessPolicy.canView(project,
                new AuthController.AuthSession("supply-1", "supplychain", "供应链")));
    }

    @Test
    void legacyTaskWithoutRoleIsVisibleOnlyToDesigners() {
        Project project = projectWithPendingTask(null);

        assertTrue(ProjectAccessPolicy.canView(project,
                new AuthController.AuthSession("designer-1", "designer", "设计师")));
        assertFalse(ProjectAccessPolicy.canView(project,
                new AuthController.AuthSession("supply-1", "supplychain", "供应链")));
    }

    @Test
    void plannerCanOnlyClaimPendingUnassignedChannelProject() {
        Project project = new Project();
        project.setType("channel_custom");
        project.setStatus("pending_planner");
        project.setPlannerId(null);
        AuthController.AuthSession planner = new AuthController.AuthSession("planner-1", "planner", "企划");

        assertTrue(ProjectAccessPolicy.canView(project, planner));
        assertTrue(ProjectAccessPolicy.canManage(project, planner));

        project.setStatus("in_progress");
        assertFalse(ProjectAccessPolicy.canView(project, planner));
        assertFalse(ProjectAccessPolicy.canManage(project, planner));
    }

    @Test
    void onlyOwningSalesCanEditChannelProjectInformation() {
        Project project = new Project();
        project.setType("channel_custom");
        project.setSalesId("sales-1");

        assertTrue(ProjectAccessPolicy.canEditProjectInformation(project,
                new AuthController.AuthSession("sales-1", "sales", "销售")));
        assertFalse(ProjectAccessPolicy.canEditProjectInformation(project,
                new AuthController.AuthSession("sales-2", "sales", "销售")));
        assertFalse(ProjectAccessPolicy.canEditProjectInformation(project,
                new AuthController.AuthSession("admin-1", "admin", "管理员")));
    }

    @Test
    void onlyOwningPlannerCanEditRegularProjectInformation() {
        Project project = new Project();
        project.setType("regular");
        project.setPlannerId("planner-1");

        assertTrue(ProjectAccessPolicy.canEditProjectInformation(project,
                new AuthController.AuthSession("planner-1", "planner", "企划")));
        assertFalse(ProjectAccessPolicy.canEditProjectInformation(project,
                new AuthController.AuthSession("planner-2", "planner", "企划")));
        assertFalse(ProjectAccessPolicy.canEditProjectInformation(project,
                new AuthController.AuthSession("sales-1", "sales", "销售")));
    }

    private Project projectWithPendingTask(String assigneeRole) {
        Project project = new Project();
        SubTask task = new SubTask();
        task.setStatus("pending");
        task.setAssigneeRole(assigneeRole);
        project.getTasks().add(task);
        return project;
    }
}
