INSERT INTO permissions
    (code, name, module, description, risk_level, enabled, created_at, updated_at)
VALUES
    ('page.workload.view', '查看工作量页', '页面', '进入工作量页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.subtasks.market.view', '查看接单市场页', '页面', '进入接单市场页面', 'normal', 1, NOW(6), NOW(6)),
    ('page.material_market.view', '查看素材广场页', '页面', '进入素材广场页面', 'normal', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    module = VALUES(module),
    description = VALUES(description),
    risk_level = VALUES(risk_level),
    enabled = VALUES(enabled),
    updated_at = VALUES(updated_at);
