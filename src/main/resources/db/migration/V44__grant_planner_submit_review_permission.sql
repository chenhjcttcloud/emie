-- 修复产品企划可看到送审按钮但提交时被权限层拒绝的问题。
INSERT IGNORE INTO permissions
    (code, name, module, description, risk_level, enabled, created_at, updated_at)
VALUES
    ('subtask.review.first.submit', '子任务提交送审', '子任务审核', '产品企划将已交付子任务提交审核', 'important', 1, NOW(6), NOW(6));

INSERT IGNORE INTO role_permissions
    (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code = 'subtask.review.first.submit'
WHERE LOWER(r.name) = 'planner';
