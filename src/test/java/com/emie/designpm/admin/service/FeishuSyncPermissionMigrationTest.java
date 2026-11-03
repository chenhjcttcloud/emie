package com.emie.designpm.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class FeishuSyncPermissionMigrationTest {
    @Test
    void registersReadPermissionWithoutChangingExistingExecutionAssignment() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:sync_permission;MODE=MySQL");
                var statement = connection.createStatement()) {
            statement.execute(
                    """
                    CREATE TABLE permissions (
                      code VARCHAR(120) UNIQUE, name VARCHAR(100), module VARCHAR(50), description TEXT,
                      risk_level VARCHAR(20), enabled BOOLEAN, created_at TIMESTAMP, updated_at TIMESTAMP
                    )
                    """);
            statement.execute(
                    """
                    INSERT INTO permissions (code, name, enabled)
                    VALUES ('admin.feishu_sync.execute', '执行飞书同步', true)
                    """);
            statement.execute("CREATE TABLE role_permissions (permission_code VARCHAR(120), effect VARCHAR(10))");
            statement.execute("INSERT INTO role_permissions VALUES ('admin.feishu_sync.execute', 'deny')");
            String migration;
            try (var input = getClass().getResourceAsStream("/db/migration/V98__add_feishu_sync_view_permission.sql")) {
                assertTrue(input != null);
                migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }

            statement.execute(migration);
            statement.execute(migration);

            try (var rows = statement.executeQuery(
                    "SELECT name, enabled, risk_level FROM permissions WHERE code = 'admin.feishu_sync.view'")) {
                assertTrue(rows.next());
                assertEquals("查看飞书同步", rows.getString("name"));
                assertTrue(rows.getBoolean("enabled"));
                assertEquals("normal", rows.getString("risk_level"));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM permissions")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT permission_code, effect FROM role_permissions")) {
                assertTrue(rows.next());
                assertEquals("admin.feishu_sync.execute", rows.getString(1));
                assertEquals("deny", rows.getString(2));
            }
        }
    }
}
