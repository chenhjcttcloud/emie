-- Align active task rules with the supplied designer points workbook; keep old rows for history.
UPDATE point_rules
SET points = 1, updated_at = NOW()
WHERE rule_code = 'D30_32';

INSERT INTO point_rules
    (rule_code, points, enabled, description, category, subcategory, quality_bonus_threshold, quality_bonus_ratio,
     quality_top_threshold, quality_top_ratio, max_total_multiplier, count_in_performance, created_at, updated_at)
VALUES
    ('D30_46', 2, TRUE, '送审及反馈处理｜修改次数、迭代响应速度', '产品设计', '落地', 0, 0, 0, 0, 1, TRUE, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    points = VALUES(points), enabled = TRUE, description = VALUES(description), category = VALUES(category),
    subcategory = VALUES(subcategory), updated_at = NOW();

UPDATE point_rules
SET enabled = FALSE, updated_at = NOW()
WHERE (rule_code LIKE 'D20\\_%' OR category IN ('A', 'B', 'E') OR rule_code = 'TASK_APPROVED')
  AND enabled = TRUE;
