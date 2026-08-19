-- 将已有子任务的设计师自评分补齐为统一评分记录，参与项目综合分计算。
INSERT INTO scoring_records
    (role, score_type, review_stage, review_status, reviewer_id, reviewer_name,
     reviewed_at, score, weight, sub_task_id, updated_at)
SELECT
    'designer', 'self', 'self', 'approved', st.designer_id, st.designer_name,
    COALESCE(st.updated_at, NOW(6)), CAST(st.self_score AS SIGNED),
    COALESCE((SELECT CAST(sc.config_value AS DECIMAL(10,4)) / 100
              FROM system_configs sc
              WHERE sc.config_key = CONCAT('scoring.', p.type, '.designer') LIMIT 1), 0.20),
    st.id, NOW(6)
FROM sub_tasks st
JOIN projects p ON p.id = st.project_id
WHERE LOWER(COALESCE(st.assignee_role, '')) = 'designer'
  AND st.self_score IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM scoring_records sr
                  WHERE sr.sub_task_id = st.id AND sr.role = 'designer');

-- 历史设计需求若未创建设计师自评记录，补齐待评分记录。
INSERT INTO design_requirement_scores
    (requirement_id, stage, role, reviewer_id, reviewer_name, status, created_at, updated_at)
SELECT d.id, 'self', 'designer', d.designer_id, d.designer_name, 'waiting', NOW(6), NOW(6)
FROM design_requirements d
WHERE d.designer_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM design_requirement_scores s
                  WHERE s.requirement_id = d.id AND s.stage = 'self');
