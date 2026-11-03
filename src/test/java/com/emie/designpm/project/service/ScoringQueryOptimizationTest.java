package com.emie.designpm.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.*;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.generate_statistics=true"
        },
        showSql = false)
@ContextConfiguration(classes = ScoringQueryOptimizationTest.Persistence.class)
class ScoringQueryOptimizationTest {
    @Configuration
    @EnableJpaRepositories(basePackageClasses = {ScoringRepository.class, ProjectRepository.class})
    @EntityScan(basePackageClasses = Project.class)
    static class Persistence {}

    @Autowired
    EntityManager em;

    @Autowired
    ScoringRepository scores;

    @Autowired
    ProjectRepository projects;

    @Test
    void countMatchesListForLegacyStatesDuplicatesRejectedAndVisibility() {
        Project visible = project("planner-a");
        Project hidden = project("planner-b");
        record(task(visible, "delivered"), "planner", "pending", 80, null, null);
        record(task(visible, "planner_approved"), "planner", null, null, 6d, null);
        record(task(visible, "completed"), "planner", null, null, null, null);
        record(task(visible, "completed"), "planner", null, 80, null, null);
        record(task(visible, "completed"), "planner", null, null, 6d, 7d);
        record(task(visible, "delivered"), "planner", "waiting", null, null, null);
        record(task(visible, "delivered"), "planner", "approved", null, null, null);
        record(task(visible, "delivered"), "planner", "rejected", null, null, null);
        record(task(visible, "rejected"), "planner", "pending", null, null, null);
        record(task(visible, "revision_requested"), "sales", "pending", null, null, null);
        record(task(hidden, "delivered"), "planner", "pending", null, null, null);
        SubTask duplicate = task(visible, "delivered");
        record(duplicate, "planner", "pending", null, null, null);
        record(duplicate, "planner", "pending", null, null, null);
        record(duplicate, "sales", "waiting", null, null, null);
        SubTask completedFirst = task(visible, "delivered");
        record(completedFirst, "planner", "approved", 80, null, null);
        record(completedFirst, "planner", "pending", null, null, null);
        em.flush();
        em.clear();
        ProjectAccessService access = mock(ProjectAccessService.class);
        when(access.findVisibleProjectIds("planner", "planner-a")).thenReturn(List.of(visible.getId()));
        when(access.findVisibleProjectsWithTasks(new AuthSession("planner-a", "planner", "")))
                .thenAnswer(call -> projects.findAllWithTasksByIdIn(List.of(visible.getId())));
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        ProjectScoringService service = new ProjectScoringService(scores, configs, access);
        var statistics =
                em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        long count = service.countPendingScoresForUser("planner", "planner-a");
        assertEquals(4, count);
        assertEquals(1, statistics.getPrepareStatementCount(), "计数不应加载项目、任务或评分实体");
        assertEquals(0, statistics.getEntityLoadCount());
        verifyNoInteractions(configs);
        long listCount = service.getPendingScoringTasks("planner", "planner-a").stream()
                .filter(item -> Boolean.TRUE.equals(item.get("isPending")))
                .count();
        assertEquals(listCount, count);
        verify(configs, times(1)).findByConfigKey("scoring.regular.planner");
        verify(configs, times(1)).findByConfigKey("scoring.regular.sales");
    }

    @Test
    void weightsAreReusedWithinRequestAndRefreshedOnNextRequest() {
        Project project = project("planner-a");
        record(task(project, "delivered"), "planner", "pending", null, null, null);
        record(task(project, "delivered"), "planner", "pending", null, null, null);
        em.flush();
        em.clear();
        ProjectAccessService access = mock(ProjectAccessService.class);
        when(access.findVisibleProjectsWithTasks(any()))
                .thenAnswer(call -> projects.findAllWithTasksByIdIn(List.of(project.getId())));
        SystemConfigRepository configs = mock(SystemConfigRepository.class);
        when(configs.findByConfigKey("scoring.regular.planner"))
                .thenReturn(Optional.of(SystemConfig.builder().configValue("40").build()))
                .thenReturn(Optional.of(SystemConfig.builder().configValue("60").build()));
        ProjectScoringService service = new ProjectScoringService(scores, configs, access);
        assertWeights(service.getPendingScoringTasks("planner", "planner-a"), .4);
        verify(configs, times(1)).findByConfigKey("scoring.regular.planner");
        assertWeights(service.getPendingScoringTasks("planner", "planner-a"), .6);
        verify(configs, times(2)).findByConfigKey("scoring.regular.planner");
    }

    @Test
    void plannerBoardFetchesTasksInOneQueryIncludingEmptyAndUnassignedProjects() {
        Project first = project("planner-a");
        task(first, "accepted");
        task(first, "pending");
        Project empty = project("planner-a");
        Project unassigned = project(null);
        unassigned.setType("channel_custom");
        unassigned.setStatus("pending_planner");
        task(unassigned, "pending");
        project("planner-b");
        em.flush();
        em.clear();
        var statistics =
                em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<Project> loaded = projects.findByPlannerIdsLight(List.of("planner-a"));
        assertEquals(3, loaded.size());
        assertTrue(loaded.stream().anyMatch(p -> p.getId().equals(empty.getId())));
        assertTrue(loaded.stream().anyMatch(p -> p.getId().equals(unassigned.getId())));
        assertTrue(loaded.stream()
                .allMatch(p ->
                        em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(p, "tasks")));
        assertEquals(3, loaded.stream().mapToInt(p -> p.getTasks().size()).sum());
        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void emptyVisibilityAndNonReviewRolesAvoidCountQuery() {
        ProjectAccessService access = mock(ProjectAccessService.class);
        ScoringRepository repository = mock(ScoringRepository.class);
        ProjectScoringService service =
                new ProjectScoringService(repository, mock(SystemConfigRepository.class), access);
        when(access.findVisibleProjectIds("planner", "none")).thenReturn(List.of());
        assertEquals(0, service.countPendingScoresForUser("planner", "none"));
        assertEquals(0, service.countPendingScoresForUser("designer", "designer-a"));
        verifyNoInteractions(repository);
        verify(access, never()).findVisibleProjectIds("designer", "designer-a");
    }

    @Test
    void executionRolesKeepTheirOriginalUnfilteredProjectTasks() {
        ProjectAccessService access = mock(ProjectAccessService.class);
        when(access.findVisibleProjectsLight("designer", "designer-a")).thenReturn(List.of());
        ProjectScoringService service = new ProjectScoringService(scores, mock(SystemConfigRepository.class), access);
        assertTrue(service.getPendingScoringTasks("designer", "designer-a").isEmpty());
        verify(access).findVisibleProjectsLight("designer", "designer-a");
        verify(access, never()).findVisibleProjectsWithTasks(any());
    }

    @SuppressWarnings("unchecked")
    private void assertWeights(List<Map<String, Object>> items, double expected) {
        assertEquals(2, items.size());
        items.forEach(item -> assertEquals(
                expected,
                ((List<Map<String, Object>>) item.get("scoringRecords"))
                        .getFirst()
                        .get("weight")));
    }

    private Project project(String planner) {
        Project project = new Project();
        project.setType("regular");
        project.setPlannerId(planner);
        project.setDeadline("2026-12-31");
        em.persist(project);
        return project;
    }

    private SubTask task(Project project, String status) {
        SubTask task = new SubTask();
        task.setProject(project);
        task.setName("查询回归");
        task.setStatus(status);
        task.setPlannedDate("2026-12-01");
        em.persist(task);
        return task;
    }

    private void record(SubTask task, String role, String status, Integer score, Double aesthetics, Double innovation) {
        ScoringRecord record = new ScoringRecord();
        record.setSubTask(task);
        record.setRole(role);
        record.setReviewStatus(status);
        record.setScore(score);
        record.setAesthetics(aesthetics);
        record.setInnovation(innovation);
        record.setWeight(1d);
        em.persist(record);
    }
}
