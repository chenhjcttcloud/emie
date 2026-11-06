package com.emie.designpm.points.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PointLedgerAssociationServiceTest {
    private JdbcTemplate jdbc;
    private PointLedgerAssociationService service;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        service = new PointLedgerAssociationService(new NamedParameterJdbcTemplate(source));
        jdbc.execute("CREATE TABLE projects(id BIGINT,product_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE sub_tasks(id BIGINT,name VARCHAR(100),project_id BIGINT)");
        jdbc.execute("CREATE TABLE point_ledgers(id BIGINT,user_id VARCHAR(100),sub_task_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE point_adjustment_ledgers(id BIGINT,user_id VARCHAR(100),source_type VARCHAR(40),source_id BIGINT)");
        jdbc.execute("CREATE TABLE task_withdrawals(id BIGINT,user_id VARCHAR(100),sub_task_id BIGINT)");
        jdbc.execute("CREATE TABLE point_appeals(id BIGINT,applicant_user_id VARCHAR(100),point_ledger_id BIGINT)");
        jdbc.execute("CREATE TABLE design_requirements(id BIGINT,name VARCHAR(100))");
        jdbc.execute("INSERT INTO projects VALUES(100,'本人项目'),(101,'他人项目')");
        jdbc.execute("INSERT INTO sub_tasks VALUES(10,'本人任务',100),(11,'他人任务',101)");
        jdbc.execute("INSERT INTO point_ledgers VALUES(1,'d1',10),(2,'d2',11),(3,'d1',10)");
    }

    @Test
    void queriesOnlyRequestedRowsOwnedByTheSignedInUserEvenWithForeignIds() {
        jdbc.execute(
                "INSERT INTO point_adjustment_ledgers VALUES(1,'d1','MATERIAL_MARKET',100),(2,'d2','MATERIAL_MARKET',101),(3,'d1','MATERIAL_MARKET',100)");
        var associations = service.associations("d1", List.of(1L, 2L), List.of(1L, 2L));
        assertEquals(java.util.Set.of("L-1", "A-1"), associations.keySet());
        assertEquals(
                Map.of("projectId", 100L, "projectName", "本人项目", "subTaskId", 10L, "subTaskName", "本人任务"),
                associations.get("L-1"));
        assertEquals(Map.of("projectId", 100L, "projectName", "本人项目"), associations.get("A-1"));
        assertTrue(service.associations("d1' OR '1'='1", List.of(1L, 2L), List.of(1L, 2L))
                .isEmpty());
    }

    @Test
    void resolvesTypedSourcesWithoutConfusingTheirIdentifiers() {
        jdbc.execute("INSERT INTO design_requirements VALUES(100,'设计需求')");
        jdbc.execute("INSERT INTO task_withdrawals VALUES(1,'d1',10),(2,'d2',11)");
        jdbc.execute("INSERT INTO point_appeals VALUES(1,'d1',1),(2,'d2',2),(3,'d1',2)");
        jdbc.execute(
                "INSERT INTO point_adjustment_ledgers VALUES(1,'d1','MATERIAL_MARKET',100),(2,'d1','DESIGN_REQUIREMENT',100),(3,'d1','TASK_WITHDRAWAL',1),(4,'d1','APPEAL',1),(5,'d1','MANUAL',100),(6,'d1','PO_PROGRESS',100),(7,'d1','MATERIAL_FREE_RATING',100),(8,'d2','MATERIAL_MARKET',100),(9,'d1','TASK_WITHDRAWAL',2),(10,'d1','APPEAL',2),(11,'d1','APPEAL',3)");
        var associations = service.associations("d1", List.of(), List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));
        assertEquals(Map.of("projectId", 100L, "projectName", "本人项目"), associations.get("A-1"));
        assertEquals(Map.of("requirementId", 100L, "requirementName", "设计需求"), associations.get("A-2"));
        for (String key : List.of("A-3", "A-4")) {
            assertEquals(100L, associations.get(key).get("projectId"));
            assertEquals(10L, associations.get(key).get("subTaskId"));
            assertEquals("本人任务", associations.get(key).get("subTaskName"));
        }
        for (String key : List.of("A-5", "A-6", "A-7", "A-9", "A-10", "A-11")) {
            assertEquals(Map.of(), associations.get(key));
        }
        assertFalse(associations.containsKey("A-8"));
    }

    @Test
    void deletedAssociationsNeverProduceClickableTargets() {
        jdbc.execute("DELETE FROM projects WHERE id=100");
        jdbc.execute("INSERT INTO point_ledgers VALUES(4,'d1',999)");
        jdbc.execute(
                "INSERT INTO point_adjustment_ledgers VALUES(1,'d1','MATERIAL_MARKET',100),(2,'d1','DESIGN_REQUIREMENT',999)");
        var associations = service.associations("d1", List.of(1L, 4L), List.of(1L, 2L));
        assertEquals(Map.of("subTaskName", "本人任务"), associations.get("L-1"));
        assertEquals(Map.of(), associations.get("L-4"));
        assertEquals(Map.of(), associations.get("A-1"));
        assertEquals(Map.of(), associations.get("A-2"));
    }

    @Test
    void emptyIdsSkipQueriesAndNullIdsAreIgnored() {
        var query = mock(NamedParameterJdbcTemplate.class);
        var emptyService = new PointLedgerAssociationService(query);
        assertTrue(emptyService
                .associations("d1", List.of(), Arrays.asList((Long) null))
                .isEmpty());
        verifyNoInteractions(query);
        assertThrows(IllegalArgumentException.class, () -> emptyService.associations("", List.of(), List.of()));
    }
}
