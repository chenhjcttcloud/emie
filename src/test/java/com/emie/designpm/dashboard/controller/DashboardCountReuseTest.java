package com.emie.designpm.dashboard.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.User;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectService;
import com.emie.designpm.reference.repository.DepartmentRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DashboardCountReuseTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectService service = mock(ProjectService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SubTaskRepository tasks = mock(SubTaskRepository.class);
    private final DashboardController controller = new DashboardController(
            projects, mock(ScoringRepository.class), service, tasks, mock(DepartmentRepository.class), users);

    @Test
    void fullDashboardReusesBadgeCount() {
        when(service.findVisibleProjectIds("admin", "viewer")).thenReturn(List.of(1L));
        when(service.getNavigationBadgeStats("admin", "viewer")).thenReturn(Map.of("pendingScoreCount", 7L));
        Map<String, Object> body = controller
                .getFullDashboard("ignored", "ignored", "mine", false, request("admin"))
                .getBody();
        assertEquals(7L, ((Map<?, ?>) body.get("stats")).get("pendingScore"));
        assertEquals(7L, ((Map<?, ?>) body.get("badgeStats")).get("pendingScoreCount"));
        verify(service, never()).countPendingScoresForUser(anyString(), anyString());
        verify(service, times(1)).getNavigationBadgeStats("admin", "viewer");
    }

    @Test
    void plannerAllKeepsItsDistinctAdminCount() {
        when(users.findByRole("planner"))
                .thenReturn(List.of(User.builder().userId("viewer").build()));
        Project project = new Project();
        project.setId(1L);
        when(projects.findByPlannerViewLight("viewer")).thenReturn(List.of(project));
        when(service.getNavigationBadgeStats("planner", "viewer")).thenReturn(Map.of("pendingScoreCount", 2L));
        when(service.countPendingScoresForUser("admin", "viewer")).thenReturn(9L);
        Map<String, Object> body = controller
                .getFullDashboard("ignored", "ignored", "all", false, request("planner"))
                .getBody();
        assertEquals(9L, ((Map<?, ?>) body.get("stats")).get("pendingScore"));
        assertEquals(2L, ((Map<?, ?>) body.get("badgeStats")).get("pendingScoreCount"));
        verify(service, times(1)).countPendingScoresForUser("admin", "viewer");
    }

    @Test
    void dashboardCountsReuseSnapshotsWithoutLoadingEntitiesOrRepeatingAggregates() {
        List<Long> ids = List.of(1L, 2L, 3L);
        when(service.findVisibleProjectIds("admin", "viewer")).thenReturn(ids);
        when(service.getNavigationBadgeStats("admin", "viewer")).thenReturn(Map.of("pendingScoreCount", 7L));
        List<ProjectRepository.DashboardProjectProjection> projectFacts = List.of(
                projectFact(1L, "channel_custom", "in_progress"),
                projectFact(2L, "regular", "planner_accepted"),
                projectFact(3L, "channel_custom", "completed"));
        when(projects.findDashboardProjectsByIdIn(ids)).thenReturn(projectFacts);
        List<SubTaskRepository.DashboardTaskProjection> taskFacts = List.of(
                taskFact(11L, 1L, "pending"), taskFact(12L, 1L, "delivered"),
                taskFact(13L, 1L, "approved"), taskFact(14L, 1L, "completed"),
                taskFact(15L, 2L, "accepted"), taskFact(16L, 2L, "rejected"),
                taskFact(17L, 3L, "completed"), taskFact(18L, 3L, "pending"));
        when(tasks.findDashboardTasksByProjectIds(ids)).thenReturn(taskFacts);
        Map<?, ?> stats = (Map<?, ?>) controller
                .getFullDashboard("ignored", "ignored", "mine", false, request("admin"))
                .getBody()
                .get("stats");
        assertEquals(3, stats.get("totalProjects"));
        assertEquals(2L, stats.get("channelProjects"));
        assertEquals(1L, stats.get("regularProjects"));
        assertEquals(2L, stats.get("inProgress"));
        assertEquals(8L, stats.get("allTasks"));
        assertEquals(6L, stats.get("channelTasks"));
        assertEquals(3L, stats.get("approvedTasks"));
        assertEquals(3L, stats.get("pendingTasks"));
        assertEquals(7L, stats.get("pendingScore"));
        verify(projects, never()).findByIdInAndType(any(), any());
        verify(tasks, never()).countStatusByProjectIds(any());
        verify(tasks, never()).countTasksByProjectIds(any());
        verify(projects, times(1)).findDashboardProjectsByIdIn(ids);
        verify(tasks, times(1)).findDashboardTasksByProjectIds(ids);
    }

    @Test
    void noVisibleProjectsReturnsZeroStatsWithoutSnapshotQueries() {
        when(service.findVisibleProjectIds("admin", "viewer")).thenReturn(List.of());
        when(service.getNavigationBadgeStats("admin", "viewer")).thenReturn(Map.of("pendingScoreCount", 7L));
        Map<?, ?> stats = (Map<?, ?>) controller
                .getFullDashboard("ignored", "ignored", "mine", false, request("admin"))
                .getBody()
                .get("stats");
        for (String field : List.of("allTasks", "channelTasks", "approvedTasks", "pendingTasks", "pendingScore")) {
            assertEquals(0L, stats.get(field), field);
        }
        verify(projects, never()).findDashboardProjectsByIdIn(any());
        verifyNoInteractions(tasks);
    }

    private ProjectRepository.DashboardProjectProjection projectFact(Long id, String type, String status) {
        var fact = mock(ProjectRepository.DashboardProjectProjection.class);
        when(fact.getId()).thenReturn(id);
        when(fact.getType()).thenReturn(type);
        when(fact.getStatus()).thenReturn(status);
        return fact;
    }

    private SubTaskRepository.DashboardTaskProjection taskFact(Long id, Long projectId, String status) {
        var fact = mock(SubTaskRepository.DashboardTaskProjection.class);
        when(fact.getId()).thenReturn(id);
        when(fact.getProjectId()).thenReturn(projectId);
        when(fact.getStatus()).thenReturn(status);
        when(fact.getWorkflowStage()).thenReturn("design");
        return fact;
    }

    private MockHttpServletRequest request(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("viewer", role, "测试"));
        return request;
    }
}
