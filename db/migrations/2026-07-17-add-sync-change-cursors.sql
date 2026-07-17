ALTER TABLE sub_tasks ADD COLUMN IF NOT EXISTS updated_at DATETIME NULL;
UPDATE sub_tasks SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE sub_tasks MODIFY COLUMN updated_at DATETIME NOT NULL;

ALTER TABLE scoring_records ADD COLUMN IF NOT EXISTS updated_at DATETIME NULL;
UPDATE scoring_records s JOIN sub_tasks t ON t.id = s.sub_task_id SET s.updated_at = t.updated_at WHERE s.updated_at IS NULL;
UPDATE scoring_records SET updated_at = CURRENT_TIMESTAMP WHERE updated_at IS NULL;
ALTER TABLE scoring_records MODIFY COLUMN updated_at DATETIME NOT NULL;
