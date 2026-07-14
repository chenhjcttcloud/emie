package com.emie.designpm;

import com.emie.designpm.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

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
}
