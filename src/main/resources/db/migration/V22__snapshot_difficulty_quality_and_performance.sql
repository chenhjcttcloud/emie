CREATE TABLE point_difficulty_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    difficulty_code VARCHAR(40) NOT NULL,
    multiplier DOUBLE NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_point_difficulty_code UNIQUE (difficulty_code),
    CONSTRAINT chk_point_difficulty_multiplier CHECK (multiplier > 0 AND multiplier <= 10)
);

INSERT INTO point_difficulty_configs
    (difficulty_code, multiplier, enabled, description, created_at, updated_at)
VALUES
    ('STANDARD', 1.0, TRUE, '标准任务', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('COMPLEX', 1.5, TRUE, '复杂任务', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('MAJOR', 2.0, TRUE, '重大任务', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

ALTER TABLE sub_tasks ADD COLUMN quality_bonus_threshold_snapshot INT NULL;
ALTER TABLE sub_tasks ADD COLUMN quality_bonus_ratio_snapshot DOUBLE NULL;
ALTER TABLE sub_tasks ADD COLUMN count_in_performance_snapshot BOOLEAN NULL;
ALTER TABLE point_ledgers ADD COLUMN count_in_performance BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE sub_tasks t
JOIN point_rules r ON r.rule_code = t.point_rule_code
LEFT JOIN point_difficulty_configs d
       ON d.difficulty_code = COALESCE(NULLIF(t.difficulty_code, ''), 'STANDARD')
SET t.difficulty_code = COALESCE(NULLIF(t.difficulty_code, ''), 'STANDARD'),
    t.difficulty_multiplier_snapshot = COALESCE(d.multiplier, t.difficulty_multiplier_snapshot, 1.0),
    t.quality_bonus_threshold_snapshot = COALESCE(r.quality_bonus_threshold, 0),
    t.quality_bonus_ratio_snapshot = COALESCE(r.quality_bonus_ratio, 0),
    t.count_in_performance_snapshot = COALESCE(r.count_in_performance, TRUE)
WHERE t.quality_bonus_threshold_snapshot IS NULL
   OR t.quality_bonus_ratio_snapshot IS NULL
   OR t.count_in_performance_snapshot IS NULL;
