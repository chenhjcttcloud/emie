package com.emie.designpm.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class PlannerTaskPermissionMigrationTest {
    @Test
    void legacyPlannerGainsMissingManagementCapabilitiesWithoutOverridingExplicitDenials() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:planner_permissions;MODE=MySQL");
                var statement = connection.createStatement()) {
            statement.execute(
                    """
                    CREATE TABLE roles (id BIGINT PRIMARY KEY, name VARCHAR(120));
                    INSERT INTO roles VALUES (1,'planner'),(2,'admin'),(3,'designer'),(4,'custom');
                    CREATE TABLE permissions (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(120) UNIQUE, name VARCHAR(100),
                        module VARCHAR(50), description TEXT, risk_level VARCHAR(20), enabled BOOLEAN,
                        created_at TIMESTAMP, updated_at TIMESTAMP
                    );
                    INSERT INTO permissions (code,name,enabled) VALUES ('subtask.delete','删除子任务',true);
                    CREATE TABLE role_permissions (
                        role_id BIGINT, permission_id BIGINT, effect VARCHAR(10), created_at TIMESTAMP,
                        updated_at TIMESTAMP, UNIQUE(role_id,permission_id)
                    );
                    INSERT INTO role_permissions (role_id,permission_id,effect) VALUES (1,1,'deny'),(3,1,'deny');
                    CREATE TABLE permission_versions (
                        subject_type VARCHAR(30), subject_key VARCHAR(120), version BIGINT, updated_at TIMESTAMP,
                        UNIQUE(subject_type,subject_key)
                    );
                    INSERT INTO permission_versions VALUES ('role','planner',7,NOW());
                    """);
            String migration;
            try (var resource = getClass()
                    .getResourceAsStream("/db/migration/V102__register_planner_task_management_permissions.sql")) {
                assertNotNull(resource);
                migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            statement.execute(migration);
            try (var rows = statement.executeQuery(
                    """
                    SELECT p.code,rp.effect FROM role_permissions rp
                    JOIN permissions p ON p.id=rp.permission_id WHERE rp.role_id=1 ORDER BY p.code
                    """)) {
                assertTrue(rows.next());
                assertEquals("subtask.delete", rows.getString(1));
                assertEquals("deny", rows.getString(2));
                assertTrue(rows.next());
                assertEquals("subtask.edit", rows.getString(1));
                assertEquals("allow", rows.getString(2));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery(
                    "SELECT version FROM permission_versions WHERE subject_type='role' AND subject_key='planner'")) {
                assertTrue(rows.next());
                assertEquals(8, rows.getLong(1));
            }
            statement.execute(migration);
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM role_permissions WHERE role_id=2 AND effect='allow'")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(1));
            }
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM role_permissions WHERE role_id IN (3,4) AND effect='allow'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
            statement.execute("UPDATE permissions SET enabled=false WHERE code='subtask.edit'");
            statement.execute("INSERT INTO roles VALUES (5,'企划')");
            statement.execute(migration);
            try (var rows = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id
                    WHERE rp.role_id=5 AND p.code='subtask.edit'
                    """)) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
        }
    }
}
