package com.emie.designpm.points.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PointAuditServiceTest {
    JdbcTemplate jdbc;
    PointAuditService service;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        service = new PointAuditService(new NamedParameterJdbcTemplate(ds));
        jdbc.execute("CREATE TABLE task_withdrawals(id BIGINT,sub_task_id BIGINT)");
        jdbc.execute("CREATE TABLE point_appeals(id BIGINT,point_ledger_id BIGINT)");
        jdbc.execute("CREATE TABLE design_requirements(id BIGINT,name VARCHAR(100))");
        jdbc.execute("CREATE TABLE projects(id BIGINT,product_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE po_monthly_progress(id BIGINT,po_project_id BIGINT)");
        jdbc.execute("CREATE TABLE po_point_projects(id BIGINT,name VARCHAR(100))");
        jdbc.execute("CREATE TABLE users(user_id VARCHAR(100),name VARCHAR(100))");
        jdbc.execute("CREATE TABLE sub_tasks(id BIGINT,name VARCHAR(100),project_id BIGINT,designer_id VARCHAR(100))");
        jdbc.execute(
                "CREATE TABLE sub_task_delivery_versions(id BIGINT,sub_task_id BIGINT,version_no INT,submitted_by_id VARCHAR(100),point_request_id VARCHAR(36),expected_points DECIMAL(12,2),point_rule_code VARCHAR(80),submitted_at TIMESTAMP,confirmed_at TIMESTAMP,confirmed_by VARCHAR(100))");
        jdbc.execute(
                "CREATE TABLE point_ledgers(id BIGINT,user_id VARCHAR(100),sub_task_id BIGINT,rule_code VARCHAR(128),rule_description VARCHAR(255),points DECIMAL(12,2),reason VARCHAR(500),created_by VARCHAR(100),created_at TIMESTAMP,submitted_at TIMESTAMP,confirmed_at TIMESTAMP,delivery_version_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE point_adjustment_ledgers(id BIGINT,user_id VARCHAR(100),source_type VARCHAR(40),source_id BIGINT,rule_code VARCHAR(80),rule_description VARCHAR(255),points DECIMAL(12,2),reason VARCHAR(500),created_by VARCHAR(100),created_at TIMESTAMP,submitted_at TIMESTAMP)");
        jdbc.execute("INSERT INTO users VALUES('d','设计师'),('p','企划')");
        jdbc.execute("INSERT INTO sub_tasks VALUES(1,'设计',10,'d')");
    }

    @Test
    void unionFiltersSummaryAndPagingPreserveFractionalAndNegativePoints() {
        jdbc.execute(
                "INSERT INTO point_ledgers(id,user_id,sub_task_id,rule_code,points,created_at) VALUES(1,'d',1,'RULE:BASE',2.5,'2026-10-08 23:59:59')");
        jdbc.execute(
                "INSERT INTO point_adjustment_ledgers(id,user_id,source_type,source_id,points,created_at) VALUES(1,'d','MANUAL',1,-1,'2026-10-08 12:00:00')");
        var r = service.logs(Map.of("from", "2026-10-08", "to", "2026-10-08"), 0, 1);
        assertEquals(2L, r.get("total"));
        assertEquals(1, ((List<?>) r.get("items")).size());
        var summary = (Map<?, ?>) r.get("summary");
        assertEquals(1.5, ((Number) summary.get("NET")).doubleValue());
        assertEquals(1L, service.logs(Map.of("sign", "negative"), 0, 20).get("total"));
        assertEquals(1L, service.logs(Map.of("source", "TASK_BASE"), 0, 20).get("total"));
    }

    @Test
    void auditDistinguishesHistoricalZeroMissingAndMismatch() {
        jdbc.execute(
                "INSERT INTO sub_task_delivery_versions(id,sub_task_id,version_no,expected_points,submitted_at,confirmed_at) VALUES(1,1,1,NULL,'2026-10-08 10:00:00',NULL),(2,1,2,0,'2026-10-08 11:00:00','2026-10-08 12:00:00'),(3,1,3,2,'2026-10-08 13:00:00','2026-10-08 14:00:00'),(4,1,4,3,'2026-10-08 15:00:00','2026-10-08 16:00:00')");
        jdbc.execute(
                "INSERT INTO point_ledgers(id,user_id,sub_task_id,rule_code,points,delivery_version_id) VALUES(1,'d',1,'RULE:CHANGE:1',2,4)");
        jdbc.execute(
                "UPDATE sub_task_delivery_versions SET submitted_by_id='d',point_rule_code='RULE',point_request_id='1'");
        var r = service.audit(Map.of(), 0, 20);
        assertEquals(1L, r.get("historicalCount"));
        assertEquals(2L, r.get("total"));
        var rows = (List<Map<String, Object>>) r.get("items");
        assertEquals(
                Set.of("MISSING_LEDGER", "AMOUNT_MISMATCH"),
                new HashSet<>(rows.stream().map(x -> x.get("ISSUE")).toList()));
    }

    @Test
    void auditDetectsWrongRecipientRuleAndPrematureBooking() {
        jdbc.execute(
                "INSERT INTO sub_task_delivery_versions(id,sub_task_id,version_no,submitted_by_id,expected_points,point_rule_code,submitted_at,confirmed_at) VALUES(1,1,1,'d',2,'R','2026-10-08 10:00:00','2026-10-08 12:00:00'),(2,1,2,'d',2,'R','2026-10-08 10:00:00','2026-10-08 12:00:00'),(3,1,3,'d',2,'R','2026-10-08 10:00:00','2026-10-08 12:00:00')");
        jdbc.execute(
                "INSERT INTO point_ledgers(id,user_id,sub_task_id,rule_code,points,delivery_version_id,created_at) VALUES(1,'p',1,'R:BASE',2,1,'2026-10-08 12:00:00'),(2,'d',1,'OTHER:BASE',2,2,'2026-10-08 12:00:00'),(3,'d',1,'R:BASE',2,3,'2026-10-08 11:00:00')");
        var r = service.audit(Map.of("userId", "d"), 0, 20);
        var rows = (List<Map<String, Object>>) r.get("items");
        assertEquals(
                Set.of("RECIPIENT_MISMATCH", "RULE_MISMATCH", "EARLY_LEDGER"),
                new HashSet<>(rows.stream().map(x -> x.get("ISSUE")).toList()));
    }
}
