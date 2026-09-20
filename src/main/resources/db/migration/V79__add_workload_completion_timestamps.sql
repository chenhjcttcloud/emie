ALTER TABLE projects ADD COLUMN completed_at DATETIME(6) NULL;
ALTER TABLE sub_tasks ADD COLUMN completed_at DATETIME(6) NULL;

-- 历史记录没有状态变更时间；以最后更新时间作为一次性近似回填。
UPDATE projects SET completed_at = updated_at WHERE status = 'completed';
UPDATE sub_tasks SET completed_at = updated_at WHERE status IN ('approved', 'completed');
