package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectRoleStatusServiceTest {
    @Test
    void plannerCannotReadExecutionTaskNamesFromProjectsOutsideEffectiveScope() {
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        when(access.visibleUsers("planner", "xiaojie", "designer"))
                .thenReturn(
                        List.of(User.builder().userId("designer").name("设计师").build()));
        when(access.findVisibleProjectIds("planner", "xiaojie")).thenReturn(List.of(1L));
        SubTask mine = task(1L, "可见任务");
        SubTask secret = task(2L, "其他企划保密任务");
        when(tasks.findByDesignerIds(List.of("designer"))).thenReturn(List.of(mine, secret));
        Map<String, Object> board = new ProjectRoleStatusService(access, tasks, projects)
                .getRoleStatus("designer", "planner", "xiaojie", "all");
        Map<?, ?> designer = (Map<?, ?>) board.get("designer");
        List<?> active = (List<?>) designer.get("activeTasks");
        assertEquals(1, active.size());
        assertEquals("可见任务", ((Map<?, ?>) active.get(0)).get("name"));
        assertFalse(board.toString().contains("保密"));
    }

    @Test
    void seeingCollaboratedProjectOwnerDoesNotExposeOtherProjectsOfThatOwner() {
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        when(access.visibleUsers("planner", "xiaojie", "planner"))
                .thenReturn(List.of(User.builder().userId("owner").name("负责人").build()));
        when(access.findVisibleProjectIds("planner", "xiaojie")).thenReturn(List.of(1L));
        Project shared = project(1L, "协作项目");
        Project other = project(2L, "其他项目");
        when(projects.findByPlannerIdsLight(List.of("owner"))).thenReturn(List.of(shared, other));
        Map<String, Object> board = new ProjectRoleStatusService(access, tasks, projects)
                .getRoleStatus("planner", "planner", "xiaojie", "all");
        List<?> active = (List<?>) ((Map<?, ?>) board.get("owner")).get("activeProjects");
        assertEquals(1, active.size());
        assertEquals(1L, ((Map<?, ?>) active.get(0)).get("id"));
        assertFalse(board.toString().contains("其他项目"));
    }

    private Project project(Long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setPlannerId("owner");
        project.setProductName(name);
        project.setStatus("in_progress");
        return project;
    }

    private SubTask task(Long projectId, String name) {
        SubTask task = new SubTask();
        task.setId(projectId * 10);
        task.setDesignerId("designer");
        task.setName(name);
        task.setStatus("accepted");
        task.setProject(project(projectId, "项目"));
        return task;
    }
}
