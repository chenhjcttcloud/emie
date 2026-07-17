-- 高频列表与统计查询：任务归属通过 project_id 关联项目，截止日期/市场用于筛选。
CREATE INDEX idx_sub_task_project_assignee_status ON sub_tasks (project_id, assignee_role, designer_id, status);
CREATE INDEX idx_project_deadline_status ON projects (deadline, status);
CREATE INDEX idx_project_market_created ON projects (target_market, created_at);
CREATE INDEX idx_notification_recipient_created ON notifications (recipient_user_id, created_at);
