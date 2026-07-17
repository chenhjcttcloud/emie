SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sub_tasks' AND column_name = 'updated_at') = 0,
              'ALTER TABLE sub_tasks ADD COLUMN updated_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE sub_tasks SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE sub_tasks MODIFY COLUMN updated_at DATETIME NOT NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'scoring_records' AND column_name = 'updated_at') = 0,
              'ALTER TABLE scoring_records ADD COLUMN updated_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE scoring_records s JOIN sub_tasks t ON t.id = s.sub_task_id SET s.updated_at = t.updated_at WHERE s.updated_at IS NULL;
UPDATE scoring_records SET updated_at = CURRENT_TIMESTAMP WHERE updated_at IS NULL;
ALTER TABLE scoring_records MODIFY COLUMN updated_at DATETIME NOT NULL;
