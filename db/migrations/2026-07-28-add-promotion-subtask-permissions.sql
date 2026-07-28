-- 为已部署权限基础迁移的环境补齐产品推广作为子任务负责人时所需的执行权限。
-- 可重复执行，不覆盖管理员在后台做出的拒绝授权。
INSERT IGNORE INTO role_permissions (role_id, permission_id, effect, created_at, updated_at)
SELECT r.id, p.id, 'allow', NOW(6), NOW(6)
FROM roles r
JOIN permissions p ON p.code IN (
    'subtask.accept',
    'subtask.deliver',
    'subtask.redeliver'
)
WHERE LOWER(r.name) IN ('promotion', 'product_promotion', 'product-promotion');
