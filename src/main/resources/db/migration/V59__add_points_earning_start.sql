INSERT INTO system_configs (config_key, config_value, config_group, description, value_type, sort_order, updated_at, updated_by)
SELECT 'points.program.earning_start', '2026-08-25', 'points', '子任务积分起算日期，起算日前创建的子任务不计积分', 'text', 24, NOW(), 'migration'
WHERE NOT EXISTS (SELECT 1 FROM system_configs WHERE config_key = 'points.program.earning_start');
