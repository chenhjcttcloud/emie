ALTER TABLE sub_tasks ADD COLUMN point_rule_code VARCHAR(80) NULL;
ALTER TABLE sub_tasks ADD COLUMN difficulty_code VARCHAR(40) NULL;
ALTER TABLE sub_tasks ADD COLUMN difficulty_multiplier_snapshot DOUBLE NULL;
ALTER TABLE sub_tasks ADD COLUMN base_point_snapshot INT NULL;
ALTER TABLE sub_tasks ADD COLUMN required_skill_tags_json TEXT NULL;

CREATE INDEX idx_sub_task_designer_rule_status
    ON sub_tasks (designer_id, point_rule_code, status);

UPDATE sub_tasks t
JOIN point_rules r ON r.rule_code = 'TASK_APPROVED'
SET t.point_rule_code = r.rule_code,
    t.difficulty_code = 'STANDARD',
    t.difficulty_multiplier_snapshot = r.difficulty_multiplier,
    t.base_point_snapshot = r.points
WHERE t.point_rule_code IS NULL;

INSERT INTO system_configs
    (config_key, config_value, config_group, description, value_type, sort_order, updated_at, updated_by)
SELECT 'points.claim.max_main_tasks', '5', 'points', '设计师可同时持有的A/B类主任务上限', 'number', 10,
       CURRENT_TIMESTAMP, 'migration'
WHERE NOT EXISTS (
    SELECT 1 FROM system_configs WHERE config_key = 'points.claim.max_main_tasks'
);
