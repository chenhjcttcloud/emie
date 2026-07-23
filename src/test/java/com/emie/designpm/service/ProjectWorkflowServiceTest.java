package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ProjectWorkflowAttempt;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ProjectWorkflowAttemptRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectWorkflowServiceTest {

    @Test
    void plannerAdvancesExecutionAndEveryRejectedReviewCreatesANewRound() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectWorkflowAttemptRepository attempts = mock(ProjectWorkflowAttemptRepository.class);
        Project project = project("regular");
        AtomicReference<ProjectWorkflowAttempt> latest = new AtomicReference<>();

        when(projects.findById(9L)).thenReturn(Optional.of(project));
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attempts.save(any(ProjectWorkflowAttempt.class))).thenAnswer(invocation -> {
            ProjectWorkflowAttempt attempt = invocation.getArgument(0);
            latest.set(attempt);
            return attempt;
        });
        when(attempts.findByProjectIdOrderByIdAsc(9L)).thenReturn(List.of());
        when(attempts.findFirstByProjectIdAndStageKeyOrderByAttemptNoDesc(9L, "design_review"))
                .thenAnswer(ignored -> Optional.ofNullable(latest.get()));
        when(attempts.countByProjectIdAndStageKey(9L, "design_review")).thenReturn(0L, 1L);
        ProjectWorkflowService service = new ProjectWorkflowService(projects, attempts);

        service.completeExecution(9L, "planner-1", "企划甲", "planner");
        assertEquals("design_review", project.getWorkflowStage());
        service.submitReview(9L, "planner-1", "企划甲", "planner");
        assertEquals(1, latest.get().getAttemptNo());
        service.review(9L, "rejected", "设计稿需要调整", "admin-1", "管理员", "admin");
        assertEquals("rejected", project.getWorkflowStatus());
        service.submitReview(9L, "planner-1", "企划甲", "planner");
        assertEquals(2, latest.get().getAttemptNo());

        ArgumentCaptor<ProjectWorkflowAttempt> captor = ArgumentCaptor.forClass(ProjectWorkflowAttempt.class);
        verify(attempts, org.mockito.Mockito.atLeast(3)).save(captor.capture());
        assertEquals("pending", latest.get().getStatus());
    }

    @Test
    void onlyAssignedChannelSalesCanReview() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectWorkflowAttemptRepository attempts = mock(ProjectWorkflowAttemptRepository.class);
        Project project = project("channel_custom");
        project.setSalesId("sales-1");
        project.setWorkflowStage("design_review");
        project.setWorkflowStatus("under_review");
        when(projects.findById(9L)).thenReturn(Optional.of(project));
        ProjectWorkflowService service = new ProjectWorkflowService(projects, attempts);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.review(9L, "approved", "", "sales-2", "其他销售", "sales"));

        assertEquals("当前用户无权审核该流程阶段", error.getMessage());
    }

    @Test
    void legacyProjectsWithNullWorkflowFieldsStillBuild() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectWorkflowAttemptRepository attempts = mock(ProjectWorkflowAttemptRepository.class);
        Project project = project("regular");
        project.setWorkflowStage(null);
        project.setWorkflowStatus(null);
        when(attempts.findByProjectIdOrderByIdAsc(9L)).thenReturn(List.of());
        ProjectWorkflowService service = new ProjectWorkflowService(projects, attempts);

        var activeWorkflow = service.build(project);
        assertEquals("design", activeWorkflow.get("currentStage"));
        assertEquals("current", activeWorkflow.get("status"));

        project.setStatus("completed");
        var completedWorkflow = service.build(project);
        assertEquals("bulk", completedWorkflow.get("currentStage"));
        assertEquals("completed", completedWorkflow.get("status"));
    }

    private Project project(String type) {
        Project project = new Project();
        project.setId(9L);
        project.setType(type);
        project.setStatus("in_progress");
        project.setPlannerId("planner-1");
        project.setPlannerName("企划甲");
        project.setWorkflowStage("design");
        project.setWorkflowStatus("current");
        return project;
    }
}
