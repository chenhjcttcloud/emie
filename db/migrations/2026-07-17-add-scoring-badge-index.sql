-- 待评分徽章按角色、审核状态和子任务关联统计。
-- 生产执行前需按 release-runbook 先备份，并确认索引不存在。
CREATE INDEX idx_scoring_role_status_task
    ON scoring_records (role, review_status, sub_task_id);
