-- 两级审核记录：生产环境使用 ddl-auto=validate，部署新版本前需先执行本脚本。
-- 脚本可重复执行；已有字段和已有角色记录不会重复创建。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'scoring_records' AND COLUMN_NAME = 'review_stage') = 0,
    'ALTER TABLE scoring_records ADD COLUMN review_stage VARCHAR(20) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'scoring_records' AND COLUMN_NAME = 'review_status') = 0,
    'ALTER TABLE scoring_records ADD COLUMN review_status VARCHAR(20) NULL DEFAULT ''pending''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'scoring_records' AND COLUMN_NAME = 'reviewer_id') = 0,
    'ALTER TABLE scoring_records ADD COLUMN reviewer_id VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'scoring_records' AND COLUMN_NAME = 'reviewer_name') = 0,
    'ALTER TABLE scoring_records ADD COLUMN reviewer_name VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'scoring_records' AND COLUMN_NAME = 'reviewed_at') = 0,
    'ALTER TABLE scoring_records ADD COLUMN reviewed_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兼容历史评分：企划为一审，销售/管理员为二审；已有得分视为已通过。
UPDATE scoring_records
SET review_stage = CASE WHEN role = 'planner' THEN 'first' ELSE 'second' END
WHERE review_stage IS NULL OR review_stage = '';

UPDATE scoring_records
SET review_status = CASE
        WHEN score IS NOT NULL OR (aesthetics IS NOT NULL AND innovation IS NOT NULL) THEN 'approved'
        WHEN role = 'planner' THEN 'pending'
        ELSE 'waiting'
    END
WHERE review_status IS NULL OR review_status = '';

-- 历史二审空记录若所属任务已经完成一审，应进入待二审而不是继续等待一审。
UPDATE scoring_records sr
JOIN sub_tasks st ON st.id = sr.sub_task_id
JOIN projects p ON p.id = st.project_id
SET sr.review_status = CASE
        WHEN p.type = 'channel_custom' AND st.status IN ('sales_approved', 'completed', 'approved') THEN 'approved'
        WHEN p.type <> 'channel_custom' AND st.status IN ('admin_approved', 'completed', 'approved') THEN 'approved'
        ELSE 'pending'
    END
WHERE sr.review_stage = 'second'
  AND sr.review_status = 'waiting'
  AND st.status <> 'delivered';

-- 已进入审核流程但缺少企划记录的子任务，补齐一审记录。
INSERT INTO scoring_records
    (role, score_type, review_stage, review_status, weight, sub_task_id)
SELECT
    'planner', 'planner', 'first',
    CASE WHEN st.status IN ('planner_approved', 'sales_approved', 'admin_approved', 'completed', 'approved')
         THEN 'approved' ELSE 'pending' END,
    COALESCE(
        (SELECT CAST(sc.config_value AS DECIMAL(10,4)) / 100
         FROM system_configs sc
         WHERE sc.config_key = CONCAT('scoring.', p.type, '.planner')
         LIMIT 1),
        0.25
    ),
    st.id
FROM sub_tasks st
JOIN projects p ON p.id = st.project_id
WHERE st.status IN ('delivered', 'planner_approved', 'sales_approved', 'admin_approved', 'completed', 'approved')
  AND NOT EXISTS (
      SELECT 1 FROM scoring_records sr
      WHERE sr.sub_task_id = st.id AND sr.role = 'planner'
  );

-- 按项目类型补齐二审记录：渠道定制由销售审核，公司常规品由管理员审核。
INSERT INTO scoring_records
    (role, score_type, review_stage, review_status, weight, sub_task_id)
SELECT
    CASE WHEN p.type = 'channel_custom' THEN 'sales' ELSE 'admin' END,
    CASE WHEN p.type = 'channel_custom' THEN 'sales' ELSE 'admin' END,
    'second',
    CASE
        WHEN p.type = 'channel_custom' AND st.status IN ('sales_approved', 'completed', 'approved') THEN 'approved'
        WHEN p.type <> 'channel_custom' AND st.status IN ('admin_approved', 'completed', 'approved') THEN 'approved'
        WHEN st.status = 'delivered' THEN 'waiting'
        ELSE 'pending'
    END,
    COALESCE(
        (SELECT CAST(sc.config_value AS DECIMAL(10,4)) / 100
         FROM system_configs sc
         WHERE sc.config_key = CONCAT(
             'scoring.', p.type, '.',
             CASE WHEN p.type = 'channel_custom' THEN 'sales' ELSE 'admin' END
         )
         LIMIT 1),
        0.25
    ),
    st.id
FROM sub_tasks st
JOIN projects p ON p.id = st.project_id
WHERE st.status IN ('delivered', 'planner_approved', 'sales_approved', 'admin_approved', 'completed', 'approved')
  AND NOT EXISTS (
      SELECT 1 FROM scoring_records sr
      WHERE sr.sub_task_id = st.id
        AND sr.role = CASE WHEN p.type = 'channel_custom' THEN 'sales' ELSE 'admin' END
  );
