package com.emie.designpm.sync.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.feishu.service.FeishuBaseService;
import com.emie.designpm.project.repository.*;
import com.emie.designpm.scoring.repository.ScoringRepository;
import com.emie.designpm.sync.repository.SyncQueueRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.generate_statistics=true"
        },
        showSql = false)
@ContextConfiguration(classes = SyncWorkerDatabaseBoundaryTest.Persistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SyncWorkerDatabaseBoundaryTest {
    @Configuration
    @EnableJpaRepositories(
            basePackageClasses = {ProjectRepository.class, ScoringRepository.class, ActivityLogRepository.class})
    @EntityScan(basePackageClasses = Project.class)
    static class Persistence {}

    @Autowired
    EntityManager em;

    @Autowired
    PlatformTransactionManager tx;

    @Autowired
    ProjectRepository projects;

    @Autowired
    SubTaskRepository tasks;

    @Autowired
    SubTaskDeliveryVersionRepository versions;

    @Autowired
    ScoringRepository scores;

    @Autowired
    ActivityLogRepository logs;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allEntityTypesReachFeishuWithDetachedSnapshotsAndNoOpenTransaction(boolean v2) {
        Long[] ids = new TransactionTemplate(tx).execute(status -> {
            ProductCategory category = new ProductCategory();
            category.setName("快照类目" + v2);
            em.persist(category);
            Project project = new Project();
            project.setType("regular");
            project.setProductCategory(category);
            project.setProductName("事务边界回归");
            project.setDeadline("2026-12-31");
            em.persist(project);
            SubTask task = new SubTask();
            task.setProject(project);
            task.setName("边界任务");
            task.setPlannedDate("2026-12-01");
            em.persist(task);
            ScoringRecord score = new ScoringRecord();
            score.setSubTask(task);
            score.setRole("planner");
            score.setScore(80);
            score.setWeight(.4);
            em.persist(score);
            ActivityLog log = new ActivityLog("边界日志", "企划", "planner", project);
            em.persist(log);
            return new Long[] {project.getId(), task.getId(), score.getId(), log.getId()};
        });
        SyncQueueRepository queue = mock(SyncQueueRepository.class);
        List<SyncQueue> items = List.of(
                item("project", ids[0]),
                item("sub_task", ids[1]),
                item("scoring_record", ids[2]),
                item("activity_log", ids[3]));
        when(queue.findTop20ByStatusAndNextRetryAtIsNullOrStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("pending"), eq("pending"), any()))
                .thenReturn(items);
        var statistics =
                em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        FeishuBaseService feishu = mock(FeishuBaseService.class, call -> {
            if (call.getMethod().getName().equals("isV2Active")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return v2;
            }
            if (call.getMethod().getName().startsWith("sync")) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "外部请求不得持有读取事务");
                long before = statistics.getPrepareStatementCount();
                for (Object argument : call.getArguments()) {
                    assertFalse(argument instanceof Project
                            || argument instanceof SubTask
                            || argument instanceof ScoringRecord
                            || argument instanceof ActivityLog);
                    if (argument instanceof FeishuBaseService.V2ProjectData data) {
                        assertEquals("快照类目" + v2, data.category());
                        assertEquals(List.of(ids[1]), data.taskIds());
                    }
                    if (argument instanceof FeishuBaseService.SubTaskSyncData data) {
                        assertEquals(80, data.firstReviewScore());
                    }
                }
                assertEquals(before, statistics.getPrepareStatementCount(), "读取快照不应触发懒加载");
                return null;
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        new SyncWorker(queue, projects, tasks, scores, logs, feishu, mock(SyncQueueService.class), null, versions, tx)
                .processQueue();
        items.forEach(item -> assertEquals("done", item.getStatus(), item.getErrorMsg()));
        assertEquals(
                4,
                mockingDetails(feishu).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().startsWith("sync"))
                        .count());
        verify(queue, times(8)).saveQueue(any());
    }

    private SyncQueue item(String type, Long id) {
        return SyncQueue.builder()
                .entityType(type)
                .entityId(id)
                .action("update")
                .status("pending")
                .retryCount(0)
                .build();
    }
}
