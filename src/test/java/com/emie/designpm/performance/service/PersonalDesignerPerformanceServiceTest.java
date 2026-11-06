package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.designrequirement.repository.DesignRequirementRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.performance.repository.MonthlyPerformanceConfigRepository;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.points.repository.StandardPointConfigRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonalDesignerPerformanceServiceTest {
    @Test
    void monthlyAndAnnualReportsContainOnlyOwnTasksAndRequirementPoints() {
        var ledgers = mock(PointLedgerRepository.class);
        var adjustments = mock(PointAdjustmentLedgerRepository.class);
        var users = mock(UserRepository.class);
        var tasks = mock(SubTaskRepository.class);
        var requirements = mock(DesignRequirementRepository.class);
        var service = new PerformanceService(
                ledgers,
                adjustments,
                users,
                mock(StandardPointConfigRepository.class),
                mock(MonthlyPerformanceConfigRepository.class),
                mock(SystemConfigRepository.class),
                tasks,
                mock(PointRuleRepository.class));
        service.designRequirements(requirements);
        var ownUser = User.builder()
                .userId("d1")
                .role("designer")
                .name("本人")
                .status("active")
                .build();
        when(users.findByUserId("d1")).thenReturn(Optional.of(ownUser));
        when(users.findByUserIdIn(any())).thenReturn(List.of(ownUser));
        when(ledgers.sumPerformancePointsByMonth(eq("2026-10"), any(), any()))
                .thenReturn(List.of(new Object[] {"d1", 12d}, new Object[] {"d2", 999d}));
        when(adjustments.sumPointsByMonth(eq("2026-10"), any(), any()))
                .thenReturn(List.of(new Object[] {"d1", 5d}, new Object[] {"d2", 50d}));
        when(ledgers.sumPerformancePointsBetweenMonths("2026-01", "2026-12"))
                .thenReturn(List.of(new Object[] {"d1", 100d}, new Object[] {"d2", 999d}));
        when(adjustments.sumPointsBetweenMonths("2026-01", "2026-12"))
                .thenReturn(List.of(new Object[] {"d1", 5d}, new Object[] {"d2", 50d}));
        var project = new Project();
        project.setProductName("本人项目");
        project.setType("regular");
        var ownTask = new SubTask();
        ownTask.setId(10L);
        ownTask.setName("本人任务");
        ownTask.setDesignerId("d1");
        ownTask.setCompletedAt(LocalDateTime.of(2026, 10, 2, 12, 0));
        ownTask.setProject(project);
        var otherTask = new SubTask();
        otherTask.setId(11L);
        otherTask.setName("他人任务");
        otherTask.setDesignerId("d2");
        when(tasks.findDesignerTasksCompletedBetween(any(), any())).thenReturn(List.of(otherTask, ownTask));
        var ownLedger = new PointLedger();
        ownLedger.setSubTaskId(10L);
        ownLedger.setUserId("d1");
        ownLedger.setPoints(12d);
        ownLedger.setCountInPerformance(true);
        when(ledgers.findBySubTaskIdIn(List.of(10L))).thenReturn(List.of(ownLedger));
        var ownRequirement = new DesignRequirement();
        ownRequirement.setId(20L);
        ownRequirement.setName("本人需求");
        ownRequirement.setDesignerId("d1");
        ownRequirement.setDeliveredAt(LocalDateTime.of(2026, 10, 3, 12, 0));
        var otherRequirement = new DesignRequirement();
        otherRequirement.setId(21L);
        otherRequirement.setName("他人需求");
        otherRequirement.setDesignerId("d2");
        when(requirements.findCompletedDesignerRequirementsBetween(any(), any()))
                .thenReturn(List.of(otherRequirement, ownRequirement));
        var ownAward = new PointAdjustmentLedger();
        ownAward.setUserId("d1");
        ownAward.setSourceId(20L);
        ownAward.setPoints(5);
        when(adjustments.findBySourceTypeAndSourceIdIn("DESIGN_REQUIREMENT", List.of(20L)))
                .thenReturn(List.of(ownAward));

        for (String period : List.of("2026-10", "2026")) {
            Map<String, Object> report = service.designerMonthlyReport(period, "d1");
            List<?> rows = (List<?>) report.get("designers");
            assertEquals(1, rows.size());
            Map<?, ?> row = (Map<?, ?>) rows.getFirst();
            assertEquals("d1", row.get("userId"));
            assertEquals(period.length() == 4 ? 105d : 17d, row.get("score"));
            assertEquals(2, row.get("completedCount"));
            List<?> ownTasks = (List<?>) row.get("tasks");
            assertEquals(
                    List.of("本人任务", "本人需求"),
                    ownTasks.stream()
                            .map(item -> ((Map<?, ?>) item).get("name"))
                            .toList());
            assertEquals(12d, ((Map<?, ?>) ownTasks.getFirst()).get("score"));
            assertEquals(5d, ((Map<?, ?>) ownTasks.get(1)).get("score"));
        }
        verify(users, never()).findByRole(anyString());
        assertThrows(IllegalArgumentException.class, () -> service.designerMonthlyReport("2026", ""));
        assertThrows(IllegalArgumentException.class, () -> service.designerMonthlyReport("2026", (String) null));
    }
}
