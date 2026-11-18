-- Older installations may still only have the legacy task:assign capability.
-- Register the task management capabilities used by project collaboration.
INSERT IGNORE INTO permissions
    (code, name, module, description, risk_level, enabled, created_at, updated_at)
VALUES
    ('subtask.edit', '编辑子任务', '子任务', '编辑获项目授权的子任务', 'important', 1, NOW(6), NOW(6)),
    ('subtask.delete', '删除子任务', '子任务', '删除获项目授权且符合状态要求的子任务', 'high', 1, NOW(6), NOW(6));

-- A pre-existing allow or deny assignment remains authoritative.
INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN ('subtask.edit', 'subtask.delete') AND p.enabled = TRUE
WHERE LOWER(r.name) IN ('planner', 'admin', '产品企划', '企划', '管理员', 'administrator');

INSERT INTO permission_versions (subject_type, subject_key, version, updated_at)
SELECT 'role',
       CASE WHEN LOWER(r.name) IN ('planner', '产品企划', '企划') THEN 'planner' ELSE 'admin' END,
       1, NOW(6)
FROM roles r
WHERE LOWER(r.name) IN ('planner', 'admin', '产品企划', '企划', '管理员', 'administrator')
ON DUPLICATE KEY UPDATE version = permission_versions.version + 1, updated_at = NOW(6);
