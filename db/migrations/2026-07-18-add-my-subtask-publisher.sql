SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sub_tasks' AND column_name = 'publisher_id') = 0,
              'ALTER TABLE sub_tasks ADD COLUMN publisher_id VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sub_tasks' AND column_name = 'publisher_name') = 0,
              'ALTER TABLE sub_tasks ADD COLUMN publisher_name VARCHAR(200) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sub_tasks' AND column_name = 'publisher_role') = 0,
              'ALTER TABLE sub_tasks ADD COLUMN publisher_role VARCHAR(50) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE sub_tasks t
JOIN projects p ON p.id = t.project_id
SET t.publisher_id = p.planner_id,
    t.publisher_name = p.planner_name,
    t.publisher_role = 'planner'
WHERE (t.publisher_id IS NULL OR t.publisher_id = '')
  AND p.planner_id IS NOT NULL AND p.planner_id <> '';

SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'sub_tasks' AND index_name = 'idx_sub_task_publisher_status') = 0,
              'CREATE INDEX idx_sub_task_publisher_status ON sub_tasks (publisher_id, status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
