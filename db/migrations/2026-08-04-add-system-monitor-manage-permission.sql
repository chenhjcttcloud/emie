INSERT INTO permissions
    (code, name, module, description, risk_level, enabled, created_at, updated_at)
VALUES
    ('admin.system_monitor.manage', '管理系统日志归档', '系统管理', '归档系统操作日志', 'high', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    risk_level = VALUES(risk_level),
    enabled = VALUES(enabled),
    updated_at = VALUES(updated_at);

INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code = 'admin.system_monitor.manage'
WHERE LOWER(r.name) = 'admin';
