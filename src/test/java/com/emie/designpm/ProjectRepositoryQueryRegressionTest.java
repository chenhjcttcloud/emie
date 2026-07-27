package com.emie.designpm;

import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.SubTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRepositoryQueryRegressionTest {

    @Test
    void salesProjectQueryFiltersBySalesId() throws NoSuchMethodException {
        Method method = ProjectRepository.class.getMethod("findBySalesId", String.class);
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String jpql = query.value().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(jpql.contains("where p.salesid = ?1"),
                "销售项目查询必须使用 salesId 过滤，避免附件和项目跨销售越权");
    }

    @Test
    void assigneeQuerySeparatesDesignerAndSupplyChainRoles() throws NoSuchMethodException {
        Method method = ProjectRepository.class.getMethod("findByAssigneeView", String.class, String.class);
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String jpql = query.value().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(jpql.contains("t.assigneerole = ?2"),
                "执行人项目查询必须按 assigneeRole 区分设计师和供应链");
        assertTrue(jpql.contains("t.status = 'pending'"),
                "只有待接单的未分配子任务才能进入角色公共视图");
    }

    @Test
    void pagedProjectListQueriesPreserveRoleScope() throws NoSuchMethodException {
        Method sales = ProjectRepository.class.getMethod("findBySalesIdsPage", java.util.List.class, String.class, Pageable.class);
        Method planner = ProjectRepository.class.getMethod("findByPlannerIdsPage", java.util.List.class, String.class, Pageable.class);

        assertTrue(sales.getAnnotation(Query.class).value().contains("p.salesId IN :userIds"));
        assertTrue(planner.getAnnotation(Query.class).value().contains("p.plannerId IN :userIds"));
    }

    @Test
    void mySubTasksIncludeTasksPublishedByCurrentUser() throws NoSuchMethodException {
        Method method = SubTaskRepository.class.getMethod("findMySubTasks", String.class);
        String jpql = method.getAnnotation(Query.class).value();
        assertTrue(jpql.contains("t.designerId = :userId OR t.publisherId = :userId"),
                "我的子任务必须同时包含本人负责和本人发布的任务");
    }
}
